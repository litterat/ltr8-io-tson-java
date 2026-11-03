package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TestDocuments;
import io.ltr8.tson.compiler.TsonCompiledMetaSchema;
import io.ltr8.tson.base.ReadException;
import io.ltr8.tson.compiler.TsonCompiledSchema;
import io.ltr8.tson.compiler.TsonSchemaCompiler;
import io.ltr8.tson.compiler.resolver.MetaKernelBootstrapResolver;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.compiler.TsonSchemaLinker;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The real proof this whole sketch works, not against small hand-built fragments: compiles the
 * ENTIRE real, registered {@code meta.tn} schema (55 entries after materialization -- see
 * {@code MetaKernelSchemaRegistryTest}) with a registry covering every factory this codebase
 * currently has, then reads real TSON data text against several of its own genuinely useful record
 * types -- including {@code top} itself, the case that motivated {@link VariantParser}'s own
 * redesign (subtypes-triggered dispatch, always compiling a declaration's own body too, not just
 * its subtypes).
 */
class MetaKernelEndToEndTest {

    private static TsonCompiledSchema compiled() {
        TsonSchema raw = MetaKernelBootstrapResolver.getMetaKernelSchema();
        TsonLinkedSchema linked = TsonSchemaLinker.linkBootstrap(raw);
        return rawCompile(linked);
    }

    /**
     * The raw {@link TsonCompiledSchema} underneath a bootstrap {@link TsonCompiledMetaSchema} --
     * unlike {@link TsonCompiledMetaSchema#reader}, {@link TsonCompiledSchema#get} reads *any*
     * entry, not just the ones with {@code constructor() == true}, which every test in this class
     * needs (e.g. {@code numeric.integer_size}, {@code meta.field_group}, ordinary records with no constructor of
     * their own). Mirrors {@link io.ltr8.tson.compiler.TsonCompiledMetaRegistry#bootstrap}'s own first
     * step exactly, without the final re-wrap that method's own return value would otherwise force.
     */
    private static TsonCompiledSchema rawCompile(TsonLinkedSchema linked) {
        return TsonSchemaCompiler.compile(linked, ValueReaderFactoryRegistry.tree());
    }

    /**
     * Every one of the real schema's entries compiles cleanly, the container constructors included:
     * {@link VariantParser} triggers on non-empty {@code subtypes} rather than non-empty {@code
     * parameters}, and always compiles a declaration's own body alongside any subtype dispatch rather than
     * treating the two as mutually exclusive (see {@link VariantParser}'s own Javadoc), so {@code array}
     * and {@code set_type} compile via their own ordinary body reader, the same as any other
     * constructor's vocabulary record. Nothing legitimate ever calls {@code compiled.get("meta.array")} in
     * practice, but there is no reason for it to fail if something did.
     */
    @Test
    void everyRealEntryCompilesCleanly() {
        TsonSchema raw = MetaKernelBootstrapResolver.getMetaKernelSchema();
        TsonLinkedSchema linked = TsonSchemaLinker.linkBootstrap(raw);
        TsonSchema registered = linked.schema();
        TsonCompiledSchema compiled = rawCompile(linked);

        for (String name : registered.entries().keySet()) {
            compiled.get(name);
        }
        assertEquals(91, registered.entries().size());
    }

    /**
     * {@code top => abstract {}} ([TSON-SCHEMA] §4.1): the root has no instances of its own, so a value at a
     * {@code top}-typed position names the subtype it is -- an untagged one is refused, and so is a tag naming
     * {@code top} itself, each naming the subtypes that would satisfy the position.
     */
    @Test
    void topIsAbstractSoAValueThereNamesItsSubtype() {
        TsonCompiledSchema compiled = compiled();

        ReadException untagged = assertThrows(ReadException.class,
                () -> compiled.get("meta.top").read(TestDocuments.document("{}")));
        ReadException self = assertThrows(ReadException.class,
                () -> compiled.get("meta.top").read(TestDocuments.document("!meta.top {}")));

        assertTrue(untagged.getMessage().contains("'meta.top' is abstract"), untagged.getMessage());
        assertTrue(self.getMessage().contains("a tag naming 'meta.top' selects nothing"), self.getMessage());
        assertTrue(self.getMessage().contains("record"), "the subtypes that would satisfy it: " + self.getMessage());
    }

    @Test
    void readsEnumsOwnMembersFieldAgainstRealData() {
        // enum => enum_type ^ { name_type?: = meta.identifier }: the pinned `name_type` is injected, and `members` reads
        // through meta.enum_set, a genuine ArrayBody.
        TsonCompiledSchema compiled = compiled();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) Dom.of((TsonValue) compiled.get("enum")
                .read(TestDocuments.document("{ members: [true false] }")));

        assertEquals(List.of("true", "false"), result.get("members"));
    }

    @Test
    void readsIntegerSizeAgainstRealData() {
        TsonCompiledSchema compiled = compiled();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) Dom.of((TsonValue) compiled.get("numeric.integer_size")
                .read(TestDocuments.document("{ bits: 32 signed: true }")));

        assertEquals(BigInteger.valueOf(32), result.get("bits"));
        assertEquals(true, result.get("signed")); // boolean => !enum [true false] -- a real Boolean in tree mode
    }

    @Test
    void readsFieldGroupAgainstRealData() {
        TsonCompiledSchema compiled = compiled();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) Dom.of((TsonValue) compiled.get("meta.field_group")
                .read(TestDocuments.document("{ members: [[foo] [bar baz]] optional_members: [baz] optional: true }")));

        assertEquals(List.of(List.of("foo"), List.of("bar", "baz")), result.get("members"));
        assertEquals(List.of("baz"), result.get("optional_members"));
        assertEquals(true, result.get("optional"));
    }

    @Test
    void readsRecordFieldAgainstRealNestedData() {
        TsonCompiledSchema compiled = compiled();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) Dom.of((TsonValue) compiled.get("meta.record_field")
                .read(TestDocuments.document("{ type: text voidable: true }")));

        assertEquals(true, result.get("voidable"));
        assertEquals(io.ltr8.tson.schema.meta.TypeRef.of("text"), result.get("type"),
                "a meta.type_ref slot holds a type's name, as the reference it is");
    }
}
