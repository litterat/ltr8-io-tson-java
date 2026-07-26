package io.ltr8.tson.schema;

import io.ltr8.tson.schema.registry.CanonicalIdentity;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.ElementState;
import io.ltr8.tson.schema.meta.FieldGroup;
import io.ltr8.tson.schema.meta.FieldState;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Token;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hand-built schemas only -- {@code tson-schema} has no dependency on {@code tson-parser}, so it
 * can't reach {@code MetaKernelParser} for a real fixture (see {@code MetaKernelSchemaRegistryTest}
 * in {@code tson-parser} for the real end-to-end check against meta-kernel.tn1 itself).
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

    private static TsonSchema schemaOf(Map<String, TypeDefinition> entries) {
        return new TsonSchema("https://example.test/s.tn1", "https://example.test/meta.tn1",
                List.of(), entries);
    }

    /**
     * A minimal stand-in for meta-kernel's own real {@code array} constructor -- just enough
     * vocabulary (one value_param-routed field, plus schema-composed defaults) to exercise {@code
     * instantiateArray}. Every field's own {@code type} is a bare {@code token} reference, not the
     * real {@code type_ref}/{@code element_state}/{@code boolean}/{@code integer} meta-kernel would
     * use -- irrelevant to {@code instantiateArray} itself (which reads {@code name}/{@code value}/
     * {@code valueParam} only), and using a name this minimal test schema doesn't otherwise declare
     * would fail {@code TsonSchemaLinker}'s own reference check for no reason relevant to what's being
     * tested here.
     */
    private static TypeDefinition arrayConstructorEntry() {
        RecordBody vocabulary = new RecordBody(List.of(), List.of(
                new RecordField("element_type", TypeRef.of("token"), FieldState.REQUIRED, Optional.empty(), Optional.of("T")),
                new RecordField("state", TypeRef.of("token"), FieldState.REQUIRED_DEFAULT,
                        Optional.of(new Token("REQUIRED", Token.Form.UNQUOTED)), Optional.empty()),
                new RecordField("unordered", TypeRef.of("token"), FieldState.REQUIRED_DEFAULT,
                        Optional.of(new Token("false", Token.Form.UNQUOTED)), Optional.empty()),
                new RecordField("unique_items", TypeRef.of("token"), FieldState.REQUIRED_DEFAULT,
                        Optional.of(new Token("false", Token.Form.UNQUOTED)), Optional.empty()),
                new RecordField("min_items", TypeRef.of("token"), FieldState.OPTIONAL, Optional.empty(), Optional.empty()),
                new RecordField("max_items", TypeRef.of("token"), FieldState.OPTIONAL, Optional.empty(), Optional.empty())),
                List.of());
        return new TypeDefinition(Optional.empty(), TypeKind.PRODUCT, List.of("T"), true, List.of(), List.of(),
                Optional.empty(), vocabulary);
    }

    /**
     * A minimal stand-in for meta-kernel's own real {@code set} constructor -- the identical field
     * shape as {@link #arrayConstructorEntry()} (same names, same {@code element_type} value_param
     * routing), but with {@code state}/{@code unordered}/{@code unique_items} tightened to {@code
     * REQUIRED_FIXED} instead of {@code array}'s own {@code REQUIRED_DEFAULT} -- deliberately
     * different literal values too ({@code REQUIRED}/{@code true}/{@code true}, matching the real
     * {@code set}'s own tightening), to prove {@code instantiateArray} reads them from *this*
     * vocabulary's own {@link RecordField#value}, not {@code array}'s.
     */
    private static TypeDefinition setConstructorEntry() {
        RecordBody vocabulary = new RecordBody(List.of(), List.of(
                new RecordField("element_type", TypeRef.of("token"), FieldState.REQUIRED, Optional.empty(), Optional.of("T")),
                new RecordField("state", TypeRef.of("token"), FieldState.REQUIRED_FIXED,
                        Optional.of(new Token("REQUIRED", Token.Form.UNQUOTED)), Optional.empty()),
                new RecordField("unordered", TypeRef.of("token"), FieldState.REQUIRED_FIXED,
                        Optional.of(new Token("true", Token.Form.UNQUOTED)), Optional.empty()),
                new RecordField("unique_items", TypeRef.of("token"), FieldState.REQUIRED_FIXED,
                        Optional.of(new Token("true", Token.Form.UNQUOTED)), Optional.empty()),
                new RecordField("min_items", TypeRef.of("token"), FieldState.OPTIONAL, Optional.empty(), Optional.empty()),
                new RecordField("max_items", TypeRef.of("token"), FieldState.OPTIONAL, Optional.empty(), Optional.empty())),
                List.of());
        return new TypeDefinition(Optional.empty(), TypeKind.PRODUCT, List.of("T"), true,
                List.of(), List.of(), Optional.empty(), vocabulary);
    }

    @Test
    void materializesAFieldsGenericTypeRefIntoASyntheticEntry() {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("token", unitEntry());
        entries.put("set", emptyRecord());
        entries.put("container", TypeDefinition.product(RecordBody.of(List.of(
                RecordField.required("members", new TypeRef("set", List.of(new TypeArgument.Ref(TypeRef.of("token")))))))));

        TsonLinkedSchema result = TsonSchemaLinker.link(schemaOf(entries), null);

        assertEquals(4, result.schema().entries().size(), "one synthetic entry beyond the original three");
        String syntheticName = result.schema().entries().keySet().stream()
                .filter(name -> !Set.of("token", "set", "container").contains(name))
                .findFirst().orElseThrow();
        assertTrue(syntheticName.startsWith("set_token_"), "readable head: " + syntheticName);

        TypeDefinition synthetic = result.schema().entries().get(syntheticName);
        assertEquals(TypeKind.REFERENCE, synthetic.kind());
        assertEquals(new TypeRef("set", List.of(new TypeArgument.Ref(TypeRef.of("token")))),
                synthetic.source().orElseThrow());

        RecordBody containerBody = (RecordBody) result.schema().entries().get("container").body();
        assertEquals(TypeRef.of(syntheticName), containerBody.fields().get(0).type());
    }

    @Test
    void materializesAnArrayApplicationIntoARealArrayBodyNotAPlaceholderReference() {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("token", unitEntry());
        entries.put("array", arrayConstructorEntry());
        entries.put("container", TypeDefinition.product(RecordBody.of(List.of(
                RecordField.required("items", new TypeRef("array", List.of(new TypeArgument.Ref(TypeRef.of("token")))))))));

        TsonLinkedSchema result = TsonSchemaLinker.link(schemaOf(entries), null);

        String syntheticName = result.schema().entries().keySet().stream()
                .filter(name -> !Set.of("token", "array", "container").contains(name))
                .findFirst().orElseThrow();
        assertTrue(syntheticName.startsWith("array_token_"), "readable head: " + syntheticName);

        TypeDefinition synthetic = result.schema().entries().get(syntheticName);
        assertEquals(TypeKind.PRODUCT, synthetic.kind());
        assertEquals(TypeRef.of("array"), synthetic.source().orElseThrow(),
                "source is the bare constructor name, matching SchemaResolver.resolveInstance's own convention");

        ArrayBody body = (ArrayBody) synthetic.body();
        assertEquals(TypeRef.of("token"), body.elementType());
        assertEquals(ElementState.REQUIRED, body.state());
        assertFalse(body.unordered());
        assertFalse(body.uniqueItems());
        assertTrue(body.minItems().isEmpty());
        assertTrue(body.maxItems().isEmpty());

        RecordBody containerBody = (RecordBody) result.schema().entries().get("container").body();
        assertEquals(TypeRef.of(syntheticName), containerBody.fields().get(0).type());
    }

    @Test
    void materializesASetApplicationTheSameWayUsingItsOwnTightenedDefaults() {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("token", unitEntry());
        entries.put("set", setConstructorEntry());
        entries.put("container", TypeDefinition.product(RecordBody.of(List.of(
                RecordField.required("items", new TypeRef("set", List.of(new TypeArgument.Ref(TypeRef.of("token")))))))));

        TsonLinkedSchema result = TsonSchemaLinker.link(schemaOf(entries), null);

        String syntheticName = result.schema().entries().keySet().stream()
                .filter(name -> !Set.of("token", "set", "container").contains(name))
                .findFirst().orElseThrow();
        assertTrue(syntheticName.startsWith("set_token_"), "readable head: " + syntheticName);

        TypeDefinition synthetic = result.schema().entries().get(syntheticName);
        assertEquals(TypeKind.PRODUCT, synthetic.kind());
        assertEquals(TypeRef.of("set"), synthetic.source().orElseThrow());

        ArrayBody body = (ArrayBody) synthetic.body();
        assertEquals(TypeRef.of("token"), body.elementType());
        // set's own tightened defaults -- REQUIRED/true/true -- not array's REQUIRED/false/false,
        // confirming instantiateArray reads them from set's own vocabulary, not array's.
        assertEquals(ElementState.REQUIRED, body.state());
        assertTrue(body.unordered());
        assertTrue(body.uniqueItems());
    }

    @Test
    void fallsBackToThePlaceholderReferenceWhenTheAppliedNameIsNotARealConstructor() {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("token", unitEntry());
        entries.put("array", emptyRecord()); // constructor: false -- not actually applicable
        entries.put("container", TypeDefinition.product(RecordBody.of(List.of(
                RecordField.required("items", new TypeRef("array", List.of(new TypeArgument.Ref(TypeRef.of("token")))))))));

        TsonLinkedSchema result = TsonSchemaLinker.link(schemaOf(entries), null);

        String syntheticName = result.schema().entries().keySet().stream()
                .filter(name -> !Set.of("token", "array", "container").contains(name))
                .findFirst().orElseThrow();
        TypeDefinition synthetic = result.schema().entries().get(syntheticName);
        assertEquals(TypeKind.REFERENCE, synthetic.kind(), "no real array constructor to instantiate from -- old placeholder shape");
    }

    @Test
    void dedupsTwoStructurallyIdenticalApplicationsToTheSameSyntheticEntry() {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("token", unitEntry());
        entries.put("set", emptyRecord());
        TypeRef setOfToken = new TypeRef("set", List.of(new TypeArgument.Ref(TypeRef.of("token"))));
        entries.put("first", TypeDefinition.product(RecordBody.of(List.of(RecordField.required("members", setOfToken)))));
        entries.put("second", TypeDefinition.product(RecordBody.of(List.of(RecordField.required("members", setOfToken)))));

        TsonLinkedSchema result = TsonSchemaLinker.link(schemaOf(entries), null);

        assertEquals(5, result.schema().entries().size(), "still only one synthetic entry, shared by both use sites");
        TypeRef firstFieldType = ((RecordBody) result.schema().entries().get("first").body()).fields().get(0).type();
        TypeRef secondFieldType = ((RecordBody) result.schema().entries().get("second").body()).fields().get(0).type();
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
        // supertypes is already the full transitive chain by the time this runs (SchemaResolver's
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
