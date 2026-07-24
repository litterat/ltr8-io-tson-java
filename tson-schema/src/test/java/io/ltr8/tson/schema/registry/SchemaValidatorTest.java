package io.ltr8.tson.schema.registry;

import io.ltr8.tson.schema.SchemaLoader;
import io.ltr8.tson.schema.SchemaValidationException;
import io.ltr8.tson.schema.TsonSchema;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hand-built schemas only -- {@code tson-schema} has no dependency on {@code tson-parser}, so it
 * can't reach {@code MetaKernelParser} for a real fixture (see {@code MetaKernelSchemaRegistryTest}
 * in {@code tson-parser} for the real end-to-end check against meta-kernel.tn1 itself).
 */
class SchemaValidatorTest {

    private static TypeDefinition unitEntry() {
        return new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), false, List.of(), List.of(),
                Optional.empty(), new Unit());
    }

    private static TypeDefinition emptyRecord() {
        return TypeDefinition.product(RecordBody.of(List.of()));
    }

    private static TsonSchema schemaOf(Map<String, TypeDefinition> entries) {
        return new TsonSchema(Optional.of("https://example.test/s.tn1"), "https://example.test/meta.tn1",
                List.of(), entries);
    }

    @Test
    void materializesAFieldsGenericTypeRefIntoASyntheticEntry() {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("token", unitEntry());
        entries.put("set", emptyRecord());
        entries.put("container", TypeDefinition.product(RecordBody.of(List.of(
                RecordField.required("members", new TypeRef("set", List.of(new TypeArgument.Ref(TypeRef.of("token")))))))));

        TsonSchema result = SchemaValidator.validate(schemaOf(entries), null);

        assertEquals(4, result.entries().size(), "one synthetic entry beyond the original three");
        String syntheticName = result.entries().keySet().stream()
                .filter(name -> !Set.of("token", "set", "container").contains(name))
                .findFirst().orElseThrow();
        assertTrue(syntheticName.startsWith("set_token_"), "readable head: " + syntheticName);

        TypeDefinition synthetic = result.entries().get(syntheticName);
        assertEquals(TypeKind.REFERENCE, synthetic.kind());
        assertEquals(new TypeRef("set", List.of(new TypeArgument.Ref(TypeRef.of("token")))),
                synthetic.source().orElseThrow());

        RecordBody containerBody = (RecordBody) result.entries().get("container").body();
        assertEquals(TypeRef.of(syntheticName), containerBody.fields().get(0).type());
    }

    @Test
    void dedupsTwoStructurallyIdenticalApplicationsToTheSameSyntheticEntry() {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("token", unitEntry());
        entries.put("set", emptyRecord());
        TypeRef setOfToken = new TypeRef("set", List.of(new TypeArgument.Ref(TypeRef.of("token"))));
        entries.put("first", TypeDefinition.product(RecordBody.of(List.of(RecordField.required("members", setOfToken)))));
        entries.put("second", TypeDefinition.product(RecordBody.of(List.of(RecordField.required("members", setOfToken)))));

        TsonSchema result = SchemaValidator.validate(schemaOf(entries), null);

        assertEquals(5, result.entries().size(), "still only one synthetic entry, shared by both use sites");
        TypeRef firstFieldType = ((RecordBody) result.entries().get("first").body()).fields().get(0).type();
        TypeRef secondFieldType = ((RecordBody) result.entries().get("second").body()).fields().get(0).type();
        assertEquals(firstFieldType, secondFieldType);
    }

    @Test
    void aTypeParameterInSourceIsValidWithoutNeedingToResolveOrMaterialize() {
        // set => <T> ~array<T> ^ {...} -- T is set's own declared parameter, not a real entry.
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("other", emptyRecord());
        TypeRef otherOfT = new TypeRef("other", List.of(new TypeArgument.Ref(TypeRef.of("T"))));
        entries.put("generic", new TypeDefinition(Optional.of(otherOfT), TypeKind.PRODUCT, List.of("T"), false,
                List.of(), List.of(), Optional.empty(), RecordBody.of(List.of())));

        TsonSchema result = SchemaValidator.validate(schemaOf(entries), null);

        // source is validated (T accepted via the parameter exception) but never materialized.
        assertEquals(otherOfT, result.entries().get("generic").source().orElseThrow());
        assertEquals(2, result.entries().size(), "no synthetic entry created for a type-parameter application in source");
    }

    @Test
    void rejectsAFieldReferencingAnUndeclaredType() {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("container", TypeDefinition.product(RecordBody.of(List.of(
                RecordField.required("field", TypeRef.of("no_such_type"))))));

        SchemaValidationException ex = assertThrows(SchemaValidationException.class,
                () -> SchemaValidator.validate(schemaOf(entries), null));
        assertTrue(ex.getMessage().contains("no_such_type"));
    }

    @Test
    void rejectsAnUnresolvedSupertype() {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("child", new TypeDefinition(Optional.empty(), TypeKind.PRODUCT, List.of(), false,
                List.of("no_such_supertype"), List.of(), Optional.empty(), RecordBody.of(List.of())));

        assertThrows(SchemaValidationException.class, () -> SchemaValidator.validate(schemaOf(entries), null));
    }

    @Test
    void rejectsAFieldGroupReferencingAnUnknownField() {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("thing", TypeDefinition.product(new RecordBody(List.of(),
                List.of(RecordField.required("a", TypeRef.of("thing"))),
                List.of(new FieldGroup(List.of("not_a_real_field"), io.ltr8.tson.schema.meta.ElementState.OPTIONAL)))));

        assertThrows(SchemaValidationException.class, () -> SchemaValidator.validate(schemaOf(entries), null));
    }

    @Test
    void mergesImportedEntriesBeforeLocalOnesAndValidatesTheWhole() {
        TsonSchema imported = schemaOf(Map.of("imported_a", emptyRecord()));
        Map<String, TsonSchema> byIdentity = Map.of(CanonicalIdentity.of("https://example.test/import.tn1"), imported);
        SchemaLoader loader = id -> Optional.ofNullable(byIdentity.get(id));

        Map<String, TypeDefinition> localEntries = new LinkedHashMap<>();
        localEntries.put("local_a", TypeDefinition.product(
                RecordBody.of(List.of(RecordField.required("field", TypeRef.of("imported_a"))))));
        TsonSchema local = new TsonSchema(Optional.of("https://example.test/importer.tn1"),
                "https://example.test/meta.tn1", List.of("https://example.test/import.tn1"), localEntries);

        TsonSchema result = SchemaValidator.validate(local, loader);

        assertEquals(Set.of("imported_a", "local_a"), result.entries().keySet());
    }

    @Test
    void rejectsAnImportThatIsNotRegistered() {
        SchemaLoader loader = id -> Optional.empty();
        TsonSchema local = new TsonSchema(Optional.of("https://example.test/importer.tn1"),
                "https://example.test/meta.tn1", List.of("https://example.test/missing.tn1"), Map.of());

        assertThrows(SchemaValidationException.class, () -> SchemaValidator.validate(local, loader));
    }

    @Test
    void rejectsACollisionBetweenALocalEntryAndAnImportedEntry() {
        TsonSchema imported = schemaOf(Map.of("shared_name", emptyRecord()));
        Map<String, TsonSchema> byIdentity = Map.of(CanonicalIdentity.of("https://example.test/import.tn1"), imported);
        SchemaLoader loader = id -> Optional.ofNullable(byIdentity.get(id));

        TsonSchema local = new TsonSchema(Optional.of("https://example.test/importer.tn1"),
                "https://example.test/meta.tn1", List.of("https://example.test/import.tn1"),
                Map.of("shared_name", emptyRecord()));

        assertThrows(SchemaValidationException.class, () -> SchemaValidator.validate(local, loader));
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

        TsonSchema result = SchemaValidator.validate(schemaOf(entries), null);

        assertEquals(Set.of("success_response", "failure_response"), Set.copyOf(result.entries().get("response").subtypes()));
        assertTrue(result.entries().get("success_response").subtypes().isEmpty());
        assertTrue(result.entries().get("token").subtypes().isEmpty());
    }

    @Test
    void subtypesPropagateTransitivelyThroughAnAlreadyTransitiveSupertypeChain() {
        // supertypes is already the full transitive chain by the time this runs (SchemaResolver's
        // own induction), so the reverse index falls out the same way with no extra closure step.
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("top", emptyRecord());
        entries.put("mid", new TypeDefinition(Optional.empty(), TypeKind.PRODUCT, List.of(), false,
                List.of("top"), List.of(), Optional.empty(), RecordBody.of(List.of())));
        entries.put("leaf", new TypeDefinition(Optional.empty(), TypeKind.PRODUCT, List.of(), false,
                List.of("mid", "top"), List.of(), Optional.empty(), RecordBody.of(List.of())));

        TsonSchema result = SchemaValidator.validate(schemaOf(entries), null);

        assertEquals(Set.of("mid", "leaf"), Set.copyOf(result.entries().get("top").subtypes()));
        assertEquals(Set.of("leaf"), Set.copyOf(result.entries().get("mid").subtypes()));
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
        Map<String, TsonSchema> byIdentity = Map.of(CanonicalIdentity.of("https://example.test/import.tn1"), imported);
        SchemaLoader loader = id -> Optional.ofNullable(byIdentity.get(id));

        Map<String, TypeDefinition> localEntries = new LinkedHashMap<>();
        localEntries.put("local_child", new TypeDefinition(Optional.empty(), TypeKind.PRODUCT, List.of(), false,
                List.of("imported_base"), List.of(), Optional.empty(), RecordBody.of(List.of())));
        TsonSchema local = new TsonSchema(Optional.of("https://example.test/importer.tn1"),
                "https://example.test/meta.tn1", List.of("https://example.test/import.tn1"), localEntries);

        TsonSchema result = SchemaValidator.validate(local, loader);

        // This importer's own merged view: the pre-existing subtype plus the newly-local one.
        assertEquals(Set.of("imported_child", "local_child"),
                Set.copyOf(result.entries().get("imported_base").subtypes()));

        // The originally-registered "imported" schema itself is untouched -- it never learns of local_child.
        assertEquals(Set.of("imported_child"), Set.copyOf(imported.entries().get("imported_base").subtypes()));
    }

    @Test
    void rejectsACollisionBetweenTwoImports() {
        TsonSchema importedOne = schemaOf(Map.of("shared_name", emptyRecord()));
        TsonSchema importedTwo = schemaOf(Map.of("shared_name", emptyRecord()));
        Map<String, TsonSchema> byIdentity = Map.of(
                CanonicalIdentity.of("https://example.test/import-one.tn1"), importedOne,
                CanonicalIdentity.of("https://example.test/import-two.tn1"), importedTwo);
        SchemaLoader loader = id -> Optional.ofNullable(byIdentity.get(id));

        TsonSchema local = new TsonSchema(Optional.of("https://example.test/importer.tn1"),
                "https://example.test/meta.tn1",
                List.of("https://example.test/import-one.tn1", "https://example.test/import-two.tn1"), Map.of());

        assertThrows(SchemaValidationException.class, () -> SchemaValidator.validate(local, loader));
    }
}
