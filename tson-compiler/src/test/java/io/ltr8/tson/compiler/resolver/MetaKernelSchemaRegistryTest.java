package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.TsonCompiledSchemaLoader;
import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.compiler.TsonSchemaParser;
import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.TsonCompiledMetaRegistry;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.TsonSchemaRegistry;
import io.ltr8.tson.schema.TsonSchemaValidationException;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.compiler.TsonSchemaLinker;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves {@code TsonSchemaRegistry}/{@code TsonSchemaLinker} (both in {@code tson-schema}) actually work
 * end-to-end against the real {@code meta-kernel.tn} fixture -- this test lives here, not in
 * {@code tson-schema}'s own test tree, because it's the only module with both {@link
 * MetaKernelBootstrapResolver} and {@code tson-schema} available (that module has no dependency on {@code
 * tson-compiler} at all).
 *
 * <p>Uses {@link TsonSchemaLinker#linkBootstrap}, not {@link TsonSchemaLinker#link} directly, to turn
 * the raw bootstrap output into a usable {@link TsonLinkedSchema} -- {@code register} refuses
 * <i>any</i> self-referential {@link TsonLinkedSchema} whose {@code schema().bootstrap() ==
 * true}, no matter how it got linked (tightened 2026-07-27, on the user's own explicit direction,
 * from an earlier version that only rejected the unlinked form -- see {@code TsonSchemaRegistry}'s own
 * Javadoc). {@code registeringTheRawBootstrapMetaKernelDirectlyIsRejected} and {@code
 * neitherRawNorLinkedBootstrapFormCanEverBeRegistered} below cover that rejection in both forms;
 * {@code registeringTheOrdinarilyResolvedNonBootstrapMetaKernelSucceeds} covers the one way
 * meta-kernel's own identity can actually end up registered -- resolved via ordinary {@code
 * SchemaResolver.resolveSchema} (which never sets {@code bootstrap}), using the loader's own
 * bootstrap branch as the structure-namespace ground truth, mirroring {@code
 * MetaTn1CompiledEndToEndTest#registerMeta}'s own pattern.
 */
class MetaKernelSchemaRegistryTest {

    @Test
    void linksTheRealMetaKernelSchemaWhoseSugarFormsTheDesugarPhaseAlreadyResolved() {
        TsonSchema raw = MetaKernelBootstrapResolver.getMetaKernelSchema();

        TsonLinkedSchema linked = TsonSchemaLinker.linkBootstrap(raw);

        // Nothing left for the linker to materialize: the bootstrap desugars its own document, so every
        // sugar form is already a declared entry by the time linking runs.
        assertEquals(raw.entries().keySet(), linked.schema().entries().keySet());

        // 47 real declarations + one desugared entry per distinct sugar form: one `array` entry per
        // distinct X used through §5.3's `[X]`/`[X]?` field-type sugar (`arguments: [type_argument]?`,
        // `fields: [record_field]`, `groups: [field_group]?`, `supertypes`/`subtypes`/`parameters:
        // [type_name]?`/`[param_name]?` -- three separate `[type_name]?` uses correctly dedup to a
        // single `array_type_name_*` entry, not three -- `elements: [tuple_element]`, `variants:
        // [type_ref]`, `members: [field_name]`). `enum`'s member set is not among them: `enum_set` is
        // a declaration the fixture writes, since `set` has no sugar of its own.
        Set<String> expectedHeads = Set.of("array_tuple_element", "array_field_name",
                "array_type_ref", "array_type_name", "array_type_argument", "array_param_name",
                "array_field_group", "array_record_field");
        Set<String> syntheticNames = new HashSet<>(linked.schema().entries().keySet());
        syntheticNames.removeIf(name -> expectedHeads.stream().noneMatch(head -> name.startsWith(head + "_")));
        assertEquals(expectedHeads.size(), syntheticNames.size());
        for (String head : expectedHeads) {
            assertTrue(syntheticNames.stream().anyMatch(name -> name.startsWith(head + "_")),
                    "expected a synthetic entry with head '" + head + "', found: " + syntheticNames);
        }

        TypeDefinition enumDef = linked.schema().entries().get("enum");
        RecordBody enumBody = (RecordBody) enumDef.body();
        RecordField membersField = enumBody.fields().stream()
                .filter(f -> f.name().equals("members"))
                .findFirst().orElseThrow();
        assertEquals(TypeRef.of("enum_set"), membersField.type());
    }

    /**
     * The raw bootstrap output, wrapped (not genuinely linked -- {@code register} only takes a
     * {@link TsonLinkedSchema} now, so there's no other way to even attempt this) is refused --
     * meta-kernel's own {@code !!meta} names itself, so nothing can resolve it the ordinary way,
     * whatever form it is in.
     */
    @Test
    void registeringTheRawBootstrapMetaKernelDirectlyIsRejected() {
        TsonSchemaRegistry registry = new TsonSchemaRegistry();
        TsonLinkedSchema unlinked = new TsonLinkedSchema(MetaKernelBootstrapResolver.getMetaKernelSchema());

        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> registry.register(unlinked));
        assertTrue(thrown.getMessage().contains("bootstrap"));
    }

    /**
     * {@code linked-ness} alone used to be what let a bootstrap-flagged schema through {@link
     * TsonSchemaRegistry#register} (back when it was a runtime {@code materialised} flag) -- no
     * longer: the guard now rejects *any* self-referential schema with {@code bootstrap() ==
     * true}, genuinely linked or not. Confirmed directly here, not just implied by {@code
     * registeringTheRawBootstrapMetaKernelDirectlyIsRejected}'s own (unlinked-only) case.
     */
    @Test
    void neitherRawNorLinkedBootstrapFormCanEverBeRegistered() {
        TsonSchema raw = MetaKernelBootstrapResolver.getMetaKernelSchema();
        TsonSchemaRegistry registry = new TsonSchemaRegistry();
        TsonLinkedSchema linked = TsonSchemaLinker.linkBootstrap(raw);

        assertTrue(raw.bootstrap());
        assertTrue(linked.schema().bootstrap());
        assertEquals(raw.id(), linked.schema().id());
        assertEquals(raw.meta(), linked.schema().meta());
        assertEquals(57, raw.entries().size());
        assertEquals(57, linked.schema().entries().size());

        assertThrows(TsonSchemaValidationException.class, () -> registry.register(new TsonLinkedSchema(raw)));
        assertThrows(TsonSchemaValidationException.class, () -> registry.register(linked));
    }

    /**
     * The one way meta-kernel's own identity can actually end up registered: resolved via ordinary
     * {@code SchemaResolver.resolveSchema} against a loader whose own bootstrap branch supplies the
     * complete structure namespace (so even a forward-referencing declaration like {@code boolean =>
     * !enum [...]} resolves correctly, the same as {@code MetaKernelBootstrapResolver}'s own two-pass logic
     * achieves, just via the generic mechanism instead) -- {@code resolveSchema} never sets {@code
     * bootstrap}, so the result passes {@link TsonSchemaRegistry#register}'s guard even though its own
     * entries are identical to the real, linked meta-kernel. Mirrors {@code
     * MetaTn1CompiledEndToEndTest#registerMeta}'s own pattern, scoped down to meta-kernel alone.
     */
    @Test
    void registeringTheOrdinarilyResolvedNonBootstrapMetaKernelSucceeds() {
        TsonSchemaRegistry registry = new TsonSchemaRegistry();

        DataBindContext context = SchemaMetaNameBinder.defaultContext();
        TsonCompiledMetaRegistry compiledRegistry = new TsonCompiledMetaRegistry(context);
        TsonCompiledSchemaLoader loader = compiledRegistry;

        String source = TsonBundledSchemas.fetch(TsonBundledSchemas.META_KERNEL_ID);
        SchemaDocument document = new TsonSchemaParser(source).parseSchemaDocument();
        TsonSchema resolved = new SchemaResolver(loader).resolveSchema(document);
        assertFalse(resolved.bootstrap());

        TsonLinkedSchema registered = registry.register(TsonSchemaLinker.link(resolved, registry));
        assertEquals(57, registered.schema().entries().size());
        assertThrows(TsonSchemaValidationException.class, () -> registry.register(registered));
    }
}
