package io.ltr8.tson.schema;

import io.ltr8.tson.schema.registry.CanonicalIdentity;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.ElementState;
import io.ltr8.tson.schema.meta.FieldGroup;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TypeArgument;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.Unit;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hand-built schemas only -- {@code tson-schema} has no dependency on {@code tson-compiler}, so it
 * can't reach {@code MetaKernelBootstrapResolver} for a real fixture (see {@code MetaKernelSchemaRegistryTest}
 * in {@code tson-compiler} for the real end-to-end check against meta-kernel.tn1 itself).
 *
 * <p>Renamed from {@code SchemaValidatorTest} (2026-07-27, alongside {@code SchemaValidator}
 * itself becoming {@link TsonSchemaLinker}) -- what's tested hasn't changed, only the class/method
 * under test and the fact that {@link TsonSchemaLinker#link} now returns a {@link TsonLinkedSchema}.
 */
class TsonSchemaLinkerTest {

    private static TypeDefinition unitEntry() {
        return new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), false, List.of(), List.of(),
                Optional.empty(), new Unit());
    }

    private static TypeDefinition emptyRecord() {
        return TypeDefinition.product(RecordBody.of(List.of()));
    }

    /**
     * {@code !!meta} is {@link TsonBundledSchemas#META_KERNEL_ID} itself -- every caller passes {@code
     * null} for {@code loader}, so the structure namespace this would otherwise supply is always
     * empty regardless of what URI it names; using the real meta-kernel identity instead lets a
     * hand-built local entry declare its own {@code constructor: true} vocabulary without tripping
     * {@code TsonSchemaLinker}'s own "only the meta-kernel may declare constructors" check, which
     * compares against that exact identity, not merely "is this schema self-referencing."
     */
    private static TsonSchema schemaOf(Map<String, TypeDefinition> entries) {
        return new TsonSchema("https://example.test/s.tn1", TsonBundledSchemas.META_KERNEL_ID,
                List.of(), entries);
    }

    /**
     * An argument-bearing type-ref reaches the linker only from a parameterized declaration's own body --
     * {@code SchemaDesugarer} (in {@code tson-compiler}) turns every other one into a real declaration before
     * resolution ever runs, so there is nothing here to synthesize an entry for. This pins that: link carries
     * the application through untouched and adds no entries at all. The behaviour it replaces -- seven tests
     * over a materialisation pass that built {@code ArrayBody}/placeholder entries from hand-written per-shape
     * assemblers -- now lives in {@code SchemaDesugarerTest}, one phase earlier and one module over.
     */
    @Test
    void anArgumentBearingFieldTypeIsCarriedThroughWithoutSynthesizingAnEntry() {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("token", unitEntry());
        entries.put("set", emptyRecord());
        TypeRef setOfToken = new TypeRef("set", List.of(new TypeArgument.Ref(TypeRef.of("token"))));
        entries.put("container", TypeDefinition.product(RecordBody.of(List.of(
                RecordField.required("members", setOfToken)))));

        TsonLinkedSchema result = TsonSchemaLinker.link(schemaOf(entries), null);

        assertEquals(Set.of("token", "set", "container"), result.schema().entries().keySet());
        RecordBody containerBody = (RecordBody) result.schema().entries().get("container").body();
        assertEquals(setOfToken, containerBody.fields().get(0).type());
    }

    @Test
    void aTypeParameterInSourceIsValidWithoutNeedingToResolveOrMaterialize() {
        // set => <T> ~array<T> ^ {...} -- T is set's own declared parameter, not a real entry.
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("other", emptyRecord());
        TypeRef otherOfT = new TypeRef("other", List.of(new TypeArgument.Ref(TypeRef.of("T"))));
        entries.put("generic", new TypeDefinition(Optional.of(otherOfT), TypeKind.PRODUCT, List.of("T"), false,
                List.of(), List.of(), Optional.empty(), RecordBody.of(List.of())));

        TsonLinkedSchema result = TsonSchemaLinker.link(schemaOf(entries), null);

        // source is validated (T accepted via the parameter exception) but never materialized.
        assertEquals(otherOfT, result.schema().entries().get("generic").source().orElseThrow());
        assertEquals(2, result.schema().entries().size(), "no synthetic entry created for a type-parameter application in source");
    }

    @Test
    void rejectsAFieldReferencingAnUndeclaredType() {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("container", TypeDefinition.product(RecordBody.of(List.of(
                RecordField.required("field", TypeRef.of("no_such_type"))))));

        TsonSchemaValidationException ex = assertThrows(TsonSchemaValidationException.class,
                () -> TsonSchemaLinker.link(schemaOf(entries), null));
        assertTrue(ex.getMessage().contains("no_such_type"));
    }

    @Test
    void rejectsAnUnresolvedSupertype() {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("child", new TypeDefinition(Optional.empty(), TypeKind.PRODUCT, List.of(), false,
                List.of("no_such_supertype"), List.of(), Optional.empty(), RecordBody.of(List.of())));

        assertThrows(TsonSchemaValidationException.class, () -> TsonSchemaLinker.link(schemaOf(entries), null));
    }

    @Test
    void rejectsAFieldGroupReferencingAnUnknownField() {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("thing", TypeDefinition.product(new RecordBody(List.of(),
                List.of(RecordField.required("a", TypeRef.of("thing"))),
                List.of(new FieldGroup(List.of("not_a_real_field"), io.ltr8.tson.schema.meta.ElementState.OPTIONAL)))));

        assertThrows(TsonSchemaValidationException.class, () -> TsonSchemaLinker.link(schemaOf(entries), null));
    }

    @Test
    void mergesImportedEntriesBeforeLocalOnesAndValidatesTheWhole() {
        TsonLinkedSchema imported = new TsonLinkedSchema(schemaOf(Map.of("imported_a", emptyRecord())));
        Map<String, TsonLinkedSchema> byIdentity = Map.of(CanonicalIdentity.of("https://example.test/import.tn1"), imported);
        TsonSchemaLoader loader = id -> Optional.ofNullable(byIdentity.get(id));

        Map<String, TypeDefinition> localEntries = new LinkedHashMap<>();
        localEntries.put("local_a", TypeDefinition.product(
                RecordBody.of(List.of(RecordField.required("field", TypeRef.of("imported_a"))))));
        TsonSchema local = new TsonSchema("https://example.test/importer.tn1",
                "https://example.test/meta.tn1", List.of("https://example.test/import.tn1"), localEntries);

        TsonLinkedSchema result = TsonSchemaLinker.link(local, loader);

        assertEquals(Set.of("imported_a", "local_a"), result.schema().entries().keySet());
    }

    @Test
    void rejectsAnImportThatIsNotRegistered() {
        TsonSchemaLoader loader = id -> Optional.empty();
        TsonSchema local = new TsonSchema("https://example.test/importer.tn1",
                "https://example.test/meta.tn1", List.of("https://example.test/missing.tn1"), Map.of());

        assertThrows(TsonSchemaValidationException.class, () -> TsonSchemaLinker.link(local, loader));
    }

    @Test
    void rejectsACollisionBetweenALocalEntryAndAnImportedEntry() {
        TsonLinkedSchema imported = new TsonLinkedSchema(schemaOf(Map.of("shared_name", emptyRecord())));
        Map<String, TsonLinkedSchema> byIdentity = Map.of(CanonicalIdentity.of("https://example.test/import.tn1"), imported);
        TsonSchemaLoader loader = id -> Optional.ofNullable(byIdentity.get(id));

        TsonSchema local = new TsonSchema("https://example.test/importer.tn1",
                "https://example.test/meta.tn1", List.of("https://example.test/import.tn1"),
                Map.of("shared_name", emptyRecord()));

        assertThrows(TsonSchemaValidationException.class, () -> TsonSchemaLinker.link(local, loader));
    }

    @Test
    void populatesSubtypesAsTheReverseOfSupertypes() {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("response", TypeDefinition.product(RecordBody.of(List.of(
                RecordField.required("status", TypeRef.of("token"))))));
        entries.put("token", unitEntry());
        entries.put("success_response", new TypeDefinition(Optional.empty(), TypeKind.PRODUCT, List.of(), false,
                List.of("response"), List.of(), Optional.empty(),
                RecordBody.of(List.of(RecordField.required("data", TypeRef.of("token"))))));
        entries.put("failure_response", new TypeDefinition(Optional.empty(), TypeKind.PRODUCT, List.of(), false,
                List.of("response"), List.of(), Optional.empty(),
                RecordBody.of(List.of(RecordField.required("error", TypeRef.of("token"))))));

        TsonLinkedSchema result = TsonSchemaLinker.link(schemaOf(entries), null);

        assertEquals(Set.of("success_response", "failure_response"),
                Set.copyOf(result.schema().entries().get("response").subtypes()));
        assertTrue(result.schema().entries().get("success_response").subtypes().isEmpty());
        assertTrue(result.schema().entries().get("token").subtypes().isEmpty());
    }

    @Test
    void subtypesPropagateTransitivelyThroughAnAlreadyTransitiveSupertypeChain() {
        // supertypes is already the full transitive chain by the time this runs (DefinitionResolver's
        // own induction), so the reverse index falls out the same way with no extra closure step.
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("top", emptyRecord());
        entries.put("mid", new TypeDefinition(Optional.empty(), TypeKind.PRODUCT, List.of(), false,
                List.of("top"), List.of(), Optional.empty(), RecordBody.of(List.of())));
        entries.put("leaf", new TypeDefinition(Optional.empty(), TypeKind.PRODUCT, List.of(), false,
                List.of("mid", "top"), List.of(), Optional.empty(), RecordBody.of(List.of())));

        TsonLinkedSchema result = TsonSchemaLinker.link(schemaOf(entries), null);

        assertEquals(Set.of("mid", "leaf"), Set.copyOf(result.schema().entries().get("top").subtypes()));
        assertEquals(Set.of("leaf"), Set.copyOf(result.schema().entries().get("mid").subtypes()));
    }

    @Test
    void importedSupertypesGainLocalSubtypesInThisSchemasOwnViewButTheOriginalRegistrationIsUntouched() {
        // imported_base already has "imported_child" as a subtype from its own home schema's
        // registration -- computeSubtypes must union with that, not replace it.
        Map<String, TypeDefinition> importedEntries = new LinkedHashMap<>();
        importedEntries.put("imported_base", new TypeDefinition(Optional.empty(), TypeKind.PRODUCT, List.of(), false,
                List.of(), List.of("imported_child"), Optional.empty(), RecordBody.of(List.of())));
        importedEntries.put("imported_child", new TypeDefinition(Optional.empty(), TypeKind.PRODUCT, List.of(), false,
                List.of("imported_base"), List.of(), Optional.empty(), RecordBody.of(List.of())));
        TsonSchema imported = schemaOf(importedEntries);
        Map<String, TsonLinkedSchema> byIdentity =
                Map.of(CanonicalIdentity.of("https://example.test/import.tn1"), new TsonLinkedSchema(imported));
        TsonSchemaLoader loader = id -> Optional.ofNullable(byIdentity.get(id));

        Map<String, TypeDefinition> localEntries = new LinkedHashMap<>();
        localEntries.put("local_child", new TypeDefinition(Optional.empty(), TypeKind.PRODUCT, List.of(), false,
                List.of("imported_base"), List.of(), Optional.empty(), RecordBody.of(List.of())));
        TsonSchema local = new TsonSchema("https://example.test/importer.tn1",
                "https://example.test/meta.tn1", List.of("https://example.test/import.tn1"), localEntries);

        TsonLinkedSchema result = TsonSchemaLinker.link(local, loader);

        // This importer's own merged view: the pre-existing subtype plus the newly-local one.
        assertEquals(Set.of("imported_child", "local_child"),
                Set.copyOf(result.schema().entries().get("imported_base").subtypes()));

        // The originally-registered "imported" schema itself is untouched -- it never learns of local_child.
        assertEquals(Set.of("imported_child"), Set.copyOf(imported.entries().get("imported_base").subtypes()));
    }

    @Test
    void rejectsACollisionBetweenTwoImports() {
        TsonLinkedSchema importedOne = new TsonLinkedSchema(schemaOf(Map.of("shared_name", emptyRecord())));
        TsonLinkedSchema importedTwo = new TsonLinkedSchema(schemaOf(Map.of("shared_name", emptyRecord())));
        Map<String, TsonLinkedSchema> byIdentity = Map.of(
                CanonicalIdentity.of("https://example.test/import-one.tn1"), importedOne,
                CanonicalIdentity.of("https://example.test/import-two.tn1"), importedTwo);
        TsonSchemaLoader loader = id -> Optional.ofNullable(byIdentity.get(id));

        TsonSchema local = new TsonSchema("https://example.test/importer.tn1",
                "https://example.test/meta.tn1",
                List.of("https://example.test/import-one.tn1", "https://example.test/import-two.tn1"), Map.of());

        assertThrows(TsonSchemaValidationException.class, () -> TsonSchemaLinker.link(local, loader));
    }
}
