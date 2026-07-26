package io.ltr8.tson.parser.resolver.schema;

import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.parser.SchemaParser;
import io.ltr8.tson.parser.ast.schema.SchemaDocument;
import io.ltr8.tson.parser.resolver.TsonAtomContext;
import io.ltr8.tson.parser.resolver.schema.compiled.ParserFactoryRegistry;
import io.ltr8.tson.parser.resolver.schema.compiled.TsonCompiledRegistry;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.SchemaRegistry;
import io.ltr8.tson.schema.SchemaValidationException;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeRef;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves {@code SchemaRegistry}/{@code SchemaValidator} (both in {@code tson-schema}) actually work
 * end-to-end against the real {@code meta-kernel.tn1} fixture -- this test lives here, not in
 * {@code tson-schema}'s own test tree, because it's the only module with both {@link
 * MetaKernelParser} and {@code tson-schema} available (that module has no dependency on {@code
 * tson-parser} at all).
 *
 * <p>Uses {@link SchemaRegistry#materializeBootstrap}, not {@link SchemaRegistry#register}, to turn
 * the raw bootstrap output into a usable {@link TsonSchema} -- {@code register} refuses <i>any</i>
 * self-referential schema whose {@code bootstrap() == true} (tightened 2026-07-26, on the user's own
 * explicit direction, from an earlier version that only rejected the unmaterialized form -- see
 * {@code SchemaRegistry}'s own Javadoc). {@code registeringTheRawBootstrapMetaKernelDirectlyIsRejected}
 * and {@code neitherRawNorMaterializedBootstrapFormCanEverBeRegistered} below cover that rejection in
 * both forms; {@code registeringTheOrdinarilyResolvedNonBootstrapMetaKernelSucceeds} covers the one
 * way meta-kernel's own identity can actually end up registered -- resolved via ordinary {@code
 * SchemaResolver.resolveAll} (which never sets {@code bootstrap}), using the coordinator's own
 * bootstrap branch as the structure-namespace ground truth, mirroring {@code
 * MetaTn1CompiledEndToEndTest#registerMeta}'s own pattern.
 */
class MetaKernelSchemaRegistryTest {

    @Test
    void materializesTheRealMetaKernelSchemaSynthesizingEveryGenericFieldTypeRef() {
        TsonSchema raw = MetaKernelParser.getMetaKernelSchema();
        SchemaRegistry registry = new SchemaRegistry();

        TsonSchema registered = registry.materializeBootstrap(raw);

        // 49 real declarations + one synthetic entry per distinct argument-bearing application:
        // enum's own `members: set<token>`, plus one `array<X>` per distinct X used through §5.3's
        // `[X]`/`[X]?` array-sugar field types elsewhere in the fixture (`arguments: [type_argument]?`,
        // `fields: [record_field]`, `groups: [field_group]?`, `supertypes`/`subtypes`/`parameters:
        // [type_name]?`/`[param_name]?` -- three separate `[type_name]?` uses correctly dedup to a
        // single `array_type_name_*` entry, not three -- `elements: [tuple_element]`, `variants:
        // [type_ref]`, `members: [field_name]`).
        Set<String> syntheticNames = new HashSet<>(registered.entries().keySet());
        syntheticNames.removeAll(raw.entries().keySet());
        Set<String> expectedHeads = Set.of("set_token", "array_tuple_element", "array_field_name",
                "array_type_ref", "array_type_name", "array_type_argument", "array_param_name",
                "array_field_group", "array_record_field");
        assertEquals(expectedHeads.size(), syntheticNames.size());
        for (String head : expectedHeads) {
            assertTrue(syntheticNames.stream().anyMatch(name -> name.startsWith(head + "_")),
                    "expected a synthetic entry with head '" + head + "', found: " + syntheticNames);
        }

        String setTokenName = syntheticNames.stream().filter(n -> n.startsWith("set_token_")).findFirst().orElseThrow();
        TypeDefinition enumDef = registered.entries().get("enum");
        RecordBody enumBody = (RecordBody) enumDef.body();
        RecordField membersField = enumBody.fields().stream()
                .filter(f -> f.name().equals("members"))
                .findFirst().orElseThrow();
        assertEquals(TypeRef.of(setTokenName), membersField.type());
    }

    /**
     * The raw bootstrap output is refused on the very first attempt, not just on a second,
     * colliding one -- {@code SchemaRegistry.register} was never the right way to consume it (see
     * its own Javadoc): meta-kernel's own {@code !!meta} names itself, so nothing can resolve it the
     * ordinary way, and the raw output specifically is still 49 entries, not the 58 a genuinely
     * materialized meta-kernel needs.
     */
    @Test
    void registeringTheRawBootstrapMetaKernelDirectlyIsRejected() {
        SchemaRegistry registry = new SchemaRegistry();

        SchemaValidationException thrown = assertThrows(SchemaValidationException.class,
                () -> registry.register(MetaKernelParser.getMetaKernelSchema()));
        assertTrue(thrown.getMessage().contains("materializeBootstrap"));
    }

    /**
     * {@code materialised()} alone used to be what let a bootstrap-flagged schema through {@link
     * SchemaRegistry#register} -- no longer: the guard now rejects *any* self-referential schema
     * with {@code bootstrap() == true}, materialized or not. Confirmed directly here, not just
     * implied by {@code registeringTheRawBootstrapMetaKernelDirectlyIsRejected}'s own (unmaterialized-
     * only) case.
     */
    @Test
    void neitherRawNorMaterializedBootstrapFormCanEverBeRegistered() {
        TsonSchema raw = MetaKernelParser.getMetaKernelSchema();
        SchemaRegistry registry = new SchemaRegistry();
        TsonSchema materialized = registry.materializeBootstrap(raw);

        assertTrue(raw.bootstrap());
        assertTrue(materialized.bootstrap());
        assertEquals(raw.id(), materialized.id());
        assertEquals(raw.meta(), materialized.meta());
        assertFalse(raw.materialised());
        assertTrue(materialized.materialised());

        assertThrows(SchemaValidationException.class, () -> registry.register(raw));
        assertThrows(SchemaValidationException.class, () -> registry.register(materialized));
    }

    /**
     * The one way meta-kernel's own identity can actually end up registered: resolved via ordinary
     * {@code SchemaResolver.resolveAll} against a coordinator whose own bootstrap branch supplies the
     * complete structure namespace (so even a forward-referencing declaration like {@code boolean =>
     * !enum [...]} resolves correctly, the same as {@code MetaKernelParser}'s own two-pass logic
     * achieves, just via the generic mechanism instead) -- {@code resolveAll} never sets {@code
     * bootstrap}, so the result passes {@link SchemaRegistry#register}'s guard even though its own
     * entries are identical to the real, materialized meta-kernel. Mirrors {@code
     * MetaTn1CompiledEndToEndTest#registerMeta}'s own pattern, scoped down to meta-kernel alone.
     */
    @Test
    void registeringTheOrdinarilyResolvedNonBootstrapMetaKernelSucceeds() {
        TsonSchema metaKernelBootstrap = MetaKernelParser.getMetaKernelSchema();
        SchemaRegistry registry = new SchemaRegistry();
        TsonSchema materializedBootstrap = registry.materializeBootstrap(metaKernelBootstrap);

        DataBindContext context = TsonAtomContext.defaultContext();
        ParserFactoryRegistry objectFactories = ParserFactoryRegistry.object(materializedBootstrap, context);
        TsonCompiledRegistry compiledRegistry = new TsonCompiledRegistry(objectFactories);
        DefaultSchemaCoordinator coordinator = new DefaultSchemaCoordinator(compiledRegistry);

        String source = BundledSchemaSource.INSTANCE.fetch(BundledSchemaSource.META_KERNEL_ID);
        SchemaDocument document = new SchemaParser(source).parseSchemaDocument();
        TsonSchema resolved = new SchemaResolver(coordinator).resolveAll(document);
        assertFalse(resolved.bootstrap());

        TsonSchema registered = registry.register(resolved);
        assertEquals(58, registered.entries().size());
        assertThrows(SchemaValidationException.class, () -> registry.register(registered));
    }
}
