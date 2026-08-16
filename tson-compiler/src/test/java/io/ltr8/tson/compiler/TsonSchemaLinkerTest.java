package io.ltr8.tson.compiler;

import io.ltr8.tson.schema.*;
import io.ltr8.tson.schema.meta.ChoiceBody;
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
 */
class TsonSchemaLinkerTest {

    private static TypeDefinition unitEntry() {
        return new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), false, List.of(), List.of(),
                Optional.empty(), new Unit());
    }

    private static TypeDefinition emptyRecord() {
        return TypeDefinition.product(RecordBody.of(List.of()));
    }

    /** A declared choice -- what {@code (A | B)} resolves to. {@code disjoint} is the linker's to derive. */
    private static TypeDefinition choiceEntry(ChoiceBody body) {
        return new TypeDefinition(Optional.empty(), TypeKind.SUM, List.of(), false, List.of(), List.of(),
                Optional.empty(), body);
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

    /** §5.4's distinct-variant rule at its plainest: the same name written twice. */
    @Test
    void rejectsAChoiceListingOneVariantTwice() {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("token", unitEntry());
        entries.put("contact", choiceEntry(new ChoiceBody(List.of(TypeRef.of("token"), TypeRef.of("token")))));

        TsonSchemaValidationException ex = assertThrows(TsonSchemaValidationException.class,
                () -> TsonSchemaLinker.link(schemaOf(entries), null));
        assertTrue(ex.getMessage().contains("lists the variant 'token' twice"), ex.getMessage());
    }

    /**
     * The case the rule exists for: §8.3 makes an alias and its target one type, so two spellings of it are
     * a duplicate an author cannot see. The diagnostic names both spellings and what they landed on.
     */
    @Test
    void rejectsTwoVariantsThatFlattenToOneType() {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("token", unitEntry());
        entries.put("nickname", TypeDefinition.reference("token"));
        entries.put("contact", choiceEntry(
                new ChoiceBody(List.of(TypeRef.of("token"), TypeRef.of("nickname")))));

        TsonSchemaValidationException ex = assertThrows(TsonSchemaValidationException.class,
                () -> TsonSchemaLinker.link(schemaOf(entries), null));
        assertTrue(ex.getMessage().contains("'token' and 'nickname' both resolve to 'token'"), ex.getMessage());
    }

    /** Two aliases of *different* types stay distinct -- flattening must not collapse what it merely renames. */
    @Test
    void acceptsAliasedVariantsThatFlattenToDifferentTypes() {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("token", unitEntry());
        entries.put("other", emptyRecord());
        entries.put("nickname", TypeDefinition.reference("token"));
        entries.put("contact", choiceEntry(
                new ChoiceBody(List.of(TypeRef.of("nickname"), TypeRef.of("other")))));

        TsonSchemaLinker.link(schemaOf(entries), null); // no exception
    }

    /**
     * A reference cycle is a separate, unimplemented diagnostic; what matters here is that the flattening
     * walk stops instead of hanging, and that a cycle produces no false duplicate.
     */
    @Test
    void aReferenceCycleAmongVariantsTerminates() {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("a", TypeDefinition.reference("b"));
        entries.put("b", TypeDefinition.reference("a"));
        entries.put("contact", choiceEntry(new ChoiceBody(List.of(TypeRef.of("a"), TypeRef.of("b")))));

        TsonSchemaLinker.link(schemaOf(entries), null); // terminates, no exception
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
        Map<String, TsonLinkedSchema> byIdentity =
                Map.of(TsonCanonicalIdentity.canonicalize("https://example.test/import.tn1"), imported);
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
        Map<String, TsonLinkedSchema> byIdentity =
                Map.of(TsonCanonicalIdentity.canonicalize("https://example.test/import.tn1"), imported);
        TsonSchemaLoader loader = id -> Optional.ofNullable(byIdentity.get(id));

        TsonSchema local = new TsonSchema("https://example.test/importer.tn1",
                "https://example.test/meta.tn1", List.of("https://example.test/import.tn1"),
                Map.of("shared_name", emptyRecord()));

        assertThrows(TsonSchemaValidationException.class, () -> TsonSchemaLinker.link(local, loader));
    }

    /**
     * The half of §2.2.2's constructor-eligibility rule that governs the target end: naming an ordinary type
     * library as {@code !!meta} is the {@code !!import} confusion, and it fails here rather than as every
     * construction in the governed schema later falling out of scope.
     */
    @Test
    void rejectsAMetaTargetThatIsNotItselfGovernedByTheMetaKernel() {
        // a plain type library: its own !!meta is an ordinary meta, so it declares no constructors
        TsonLinkedSchema library = new TsonLinkedSchema(new TsonSchema("https://example.test/lib.tn",
                "https://example.test/meta.tn1", List.of(), Map.of("uuid", unitEntry())));
        Map<String, TsonLinkedSchema> byIdentity =
                Map.of(TsonCanonicalIdentity.canonicalize("https://example.test/lib.tn"), library);
        TsonSchemaLoader loader = id -> Optional.ofNullable(byIdentity.get(id));

        TsonSchema governed = new TsonSchema("https://example.test/app.tn",
                "https://example.test/lib.tn", List.of(), Map.of("local", emptyRecord()));

        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> TsonSchemaLinker.link(governed, loader));
        // both ends named, and the fix pointed at -- this is an authoring error, not an internal one
        assertTrue(thrown.getMessage().contains("https://example.test/lib.tn"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("https://example.test/app.tn"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("!!import"), thrown.getMessage());
    }

    @Test
    void acceptsAMetaTargetThatChainsToTheMetaKernel() {
        // schemaOf's own !!meta is meta-kernel, which is exactly what makes a schema a meta-schema
        TsonLinkedSchema meta = new TsonLinkedSchema(schemaOf(Map.of("record", emptyRecord())));
        Map<String, TsonLinkedSchema> byIdentity =
                Map.of(TsonCanonicalIdentity.canonicalize("https://example.test/meta.tn"), meta);
        TsonSchemaLoader loader = id -> Optional.ofNullable(byIdentity.get(id));

        TsonSchema governed = new TsonSchema("https://example.test/app.tn",
                "https://example.test/meta.tn", List.of(), Map.of("local", emptyRecord()));

        assertEquals(Set.of("local"), TsonSchemaLinker.link(governed, loader).schema().entries().keySet());
    }

    /**
     * Eligibility is judged only on a target the loader actually produced. Absence of evidence isn't evidence
     * of ineligibility -- meta-kernel's own self-naming {@code !!meta} is unresolvable mid-registration, and
     * whether an unresolvable {@code !!meta} is an error at all belongs to whoever owns fetching.
     */
    @Test
    void anUnresolvableMetaTargetIsNotJudgedHere() {
        TsonSchemaLoader loader = id -> Optional.empty();
        TsonSchema governed = new TsonSchema("https://example.test/app.tn",
                "https://example.test/nowhere.tn", List.of(), Map.of("local", emptyRecord()));

        assertEquals(Set.of("local"), TsonSchemaLinker.link(governed, loader).schema().entries().keySet());
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
        Map<String, TsonLinkedSchema> byIdentity = Map.of(
                TsonCanonicalIdentity.canonicalize("https://example.test/import.tn1"), new TsonLinkedSchema(imported));
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
                TsonCanonicalIdentity.canonicalize("https://example.test/import-one.tn1"), importedOne,
                TsonCanonicalIdentity.canonicalize("https://example.test/import-two.tn1"), importedTwo);
        TsonSchemaLoader loader = id -> Optional.ofNullable(byIdentity.get(id));

        TsonSchema local = new TsonSchema("https://example.test/importer.tn1",
                "https://example.test/meta.tn1",
                List.of("https://example.test/import-one.tn1", "https://example.test/import-two.tn1"), Map.of());

        assertThrows(TsonSchemaValidationException.class, () -> TsonSchemaLinker.link(local, loader));
    }
}
