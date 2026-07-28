package io.ltr8.tson.parser.compiler;

import io.ltr8.tson.parser.TsonDataParser;
import io.ltr8.tson.parser.ast.Document;
import io.ltr8.tson.parser.resolver.MetaKernelBootstrapResolver;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.TsonSchemaLinker;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The real proof this whole sketch works, not against small hand-built fragments: compiles the
 * ENTIRE real, registered {@code meta-kernel.tn1} schema (58 entries after materialization -- see
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
     * needs (e.g. {@code integer_size}, {@code field_group}, ordinary records with no constructor of
     * their own). Mirrors {@link TsonCompiledMetaSchema#bootstrap}'s own first step exactly, without
     * the final re-wrap that method's own return value would otherwise force.
     */
    private static TsonCompiledSchema rawCompile(TsonLinkedSchema linked) {
        TsonCompiledSchema placeholder = new TsonCompiledSchema(linked, Map.of());
        TsonCompiledMetaSchema bootstrapMeta = new TsonCompiledMetaSchema(placeholder, ValueReaderFactoryRegistry.dom());
        return TsonSchemaCompiler.compile(linked, bootstrapMeta);
    }

    /**
     * All 58 of the real schema's entries compile cleanly now -- up from 53 once {@link
     * VariantParser} started triggering on non-empty {@code subtypes} rather than non-empty {@code
     * parameters}, and always compiling a declaration's own body alongside any subtype dispatch
     * rather than treating the two as mutually exclusive (see {@link VariantParser}'s own Javadoc).
     * {@code map}/{@code set}/{@code array_min}/{@code array_max}/{@code array_ranged} -- the
     * parameterized constructors that used to be refused outright (no subtypes to dispatch to, and
     * under the old design that meant no compiled shape at all) -- now compile via their own
     * ordinary body parser directly, the same as any other constructor's own vocabulary record.
     * Nothing legitimate ever calls {@code compiled.get("map")} in practice (see the previous
     * revision of this test for why), but there's no longer a reason for it to fail if something
     * did.
     */
    @Test
    void allFiftyEightRealEntriesCompileCleanly() {
        TsonSchema raw = MetaKernelBootstrapResolver.getMetaKernelSchema();
        TsonLinkedSchema linked = TsonSchemaLinker.linkBootstrap(raw);
        TsonSchema registered = linked.schema();
        TsonCompiledSchema compiled = rawCompile(linked);

        for (String name : registered.entries().keySet()) {
            compiled.get(name);
        }
        assertEquals(58, registered.entries().size());
    }

    @Test
    void bareTopWithNoTypeRefReadsAgainstItsOwnEmptyBody() {
        // top => top & {} -- an empty body, but also the (transitive) supertype of everything else
        // in the schema. A value with no type annotation at a top-typed position is a real, valid
        // "just a top" reading, not an error demanding one of its many subtypes be named.
        TsonCompiledSchema compiled = compiled();
        Document document = new TsonDataParser("{}").parseDocument();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) compiled.get("top").read(document.root());

        assertEquals(Map.of(), result);
    }

    @Test
    void explicitTypeRefNamingTheDeclarationItselfAlsoUsesItsOwnBody() {
        TsonCompiledSchema compiled = compiled();
        Document document = new TsonDataParser("!top {}").parseDocument();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) compiled.get("top").read(document.root());

        assertEquals(Map.of(), result);
    }

    @Test
    void readsEnumsOwnMembersFieldAgainstRealData() {
        // The exact fix under test: enum => ~atom & { members: set<token> } -- previously
        // unbuildable (set<token> fell back to an unusable placeholder), now a genuine ArrayBody.
        TsonCompiledSchema compiled = compiled();
        Document document = new TsonDataParser("{ members: [true false] }").parseDocument();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) compiled.get("enum").read(document.root());

        assertEquals(List.of("true", "false"), result.get("members"));
    }

    @Test
    void readsIntegerSizeAgainstRealData() {
        TsonCompiledSchema compiled = compiled();
        Document document = new TsonDataParser("{ bits: 32 signed: true }").parseDocument();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) compiled.get("integer_size").read(document.root());

        assertEquals(BigInteger.valueOf(32), result.get("bits"));
        assertEquals("true", result.get("signed")); // boolean => !enum [true false] -- text, not a Java boolean
    }

    @Test
    void readsFieldGroupAgainstRealData() {
        TsonCompiledSchema compiled = compiled();
        Document document = new TsonDataParser("{ members: [foo bar] state: OPTIONAL }").parseDocument();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) compiled.get("field_group").read(document.root());

        assertEquals(List.of("foo", "bar"), result.get("members"));
        assertEquals("OPTIONAL", result.get("state"));
    }

    @Test
    void readsTupleElementAgainstRealNestedData() {
        TsonCompiledSchema compiled = compiled();
        Document document = new TsonDataParser("{ element_type: { name: text arguments: [] } state: REQUIRED }").parseDocument();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) compiled.get("tuple_element").read(document.root());

        assertEquals("REQUIRED", result.get("state"));
        @SuppressWarnings("unchecked")
        Map<String, Object> elementType = (Map<String, Object>) result.get("element_type");
        assertEquals("text", elementType.get("name"));
        assertEquals(List.of(), elementType.get("arguments"));
    }
}
