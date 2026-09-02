package io.ltr8.tson.compiler;

import io.ltr8.tson.schema.*;
import io.ltr8.annotation.Annotation;
import io.ltr8.annotation.AnnotatedMap;
import io.ltr8.annotation.Annotations;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.FieldGroup;
import io.ltr8.tson.schema.meta.FieldState;
import io.ltr8.tson.schema.meta.IntegerSize;
import io.ltr8.tson.schema.meta.IntegerType;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.SourcePosition;
import io.ltr8.tson.schema.meta.TextType;
import io.ltr8.tson.schema.meta.TypeArgument;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.Unit;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hand-built schemas only -- {@code tson-schema} has no dependency on {@code tson-compiler}, so it
 * can't reach {@code MetaKernelBootstrapResolver} for a real fixture (see {@code MetaKernelSchemaRegistryTest}
 * in {@code tson-compiler} for the real end-to-end check against meta-kernel.tn itself).
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
        return new TsonSchema("https://example.test/s.tn", TsonBundledSchemas.META_KERNEL_ID,
                List.of(), entries);
    }

    /** A schema with its own identity and imports, for the origin-tracking chain below. */
    private static TsonSchema schemaOf(String id, List<String> imports, Map<String, TypeDefinition> entries) {
        return new TsonSchema(id, TsonBundledSchemas.META_KERNEL_ID, imports, entries);
    }

    /**
     * <b>An imported entry keeps the identity of the schema that declared it, however many hops away.</b>
     * Merging flattens three documents' entries into one namespace, which is what makes every reference
     * resolvable -- and would erase which document each entry was written in, leaving a read diagnostic's
     * {@code schemaPosition} pointing at a line in a file the consumer was never given. The intermediary is
     * deliberately not the answer for {@code c_type}: {@code b.tn} passed it along, {@code c.tn} wrote it.
     */
    @Test
    void aMergedEntryKeepsTheIdentityOfTheSchemaThatDeclaredIt() {
        TsonSchemaRegistry registry = new TsonSchemaRegistry();
        registry.register(TsonSchemaLinker.link(
                schemaOf("https://example.test/c.tn", List.of(), Map.of("c_type", emptyRecord())), registry));
        registry.register(TsonSchemaLinker.link(
                schemaOf("https://example.test/b.tn", List.of("https://example.test/c.tn"),
                        Map.of("b_type", emptyRecord())), registry));

        TsonLinkedSchema a = TsonSchemaLinker.link(
                schemaOf("https://example.test/a.tn", List.of("https://example.test/b.tn"),
                        Map.of("a_type", emptyRecord())), registry);

        assertEquals(Set.of("c_type", "b_type", "a_type"), a.schema().entries().keySet());
        assertEquals("example.test/c.tn", a.originOf("c_type"), "declared two imports away");
        assertEquals("example.test/b.tn", a.originOf("b_type"));
        assertEquals("example.test/a.tn", a.originOf("a_type"));
    }

    /**
     * A hand-assembled {@link TsonLinkedSchema} -- one that never went through the linker, so nothing ever
     * recorded an origin -- answers with its own identity rather than nothing at all, since an entry with no
     * recorded origin was never merged in from anywhere.
     */
    @Test
    void anUnmergedEntryOriginatesInTheSchemaHoldingIt() {
        TsonLinkedSchema linked = new TsonLinkedSchema(schemaOf(Map.of("local", emptyRecord())));

        assertEquals("https://example.test/s.tn", linked.originOf("local"));
    }

    /**
     * §5.10's arity rule at a field type. {@code set} declares no parameters, so applying it to one is the
     * author's error -- and it is caught here rather than carried, because {@code arguments} non-empty means
     * an open form and nothing else once inline sugar lifts to entries.
     */
    @Test
    void anArgumentBearingFieldTypeAgainstANonTemplateIsRejected() {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("token", unitEntry());
        entries.put("set", emptyRecord());
        entries.put("container", TypeDefinition.product(RecordBody.of(List.of(RecordField.required("members",
                new TypeRef("set", List.of(new TypeArgument.Ref(TypeRef.of("token")))))))));

        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> TsonSchemaLinker.link(schemaOf(entries), null));
        assertTrue(thrown.getMessage().contains("'set' declares no type parameters"), thrown.getMessage());
    }

    /**
     * An argument-bearing type-ref reaches the linker only from a parameterized declaration's own body --
     * {@code SchemaDesugarer} turns every other one into a real declaration before resolution ever runs, so
     * there is nothing here to synthesize an entry for. This pins that: link carries the application through
     * untouched and adds no entries at all. What the linker therefore does <em>not</em> do -- building the
     * {@code ArrayBody}/placeholder entries a materialisation pass would -- is covered in {@code
     * SchemaDesugarerTest}, one phase earlier.
     */
    @Test
    void aTypeParameterInSourceIsValidWithoutNeedingToResolveOrMaterialize() {
        // set => <T> ~array<T> ^ {...} -- T is set's own declared parameter, not a real entry.
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        // A one-parameter template, so the application's arity is right and the only question left is
        // whether `T` -- a parameter, not an entry -- is accepted in the argument.
        entries.put("other", new TypeDefinition(Optional.empty(), TypeKind.PRODUCT, List.of("U"), false,
                List.of(), List.of(), Optional.empty(),
                RecordBody.of(List.of(RecordField.required("v", TypeRef.of("U"))))));
        TypeRef otherOfT = new TypeRef("other", List.of(new TypeArgument.Ref(TypeRef.of("T"))));
        entries.put("generic", new TypeDefinition(Optional.of(otherOfT), TypeKind.PRODUCT, List.of("T"), false,
                List.of(), List.of(), Optional.empty(), RecordBody.of(List.of())));

        TsonLinkedSchema result = TsonSchemaLinker.link(schemaOf(entries), null);

        // source is validated (T accepted via the parameter exception) but never materialized.
        assertEquals(otherOfT, result.schema().entries().get("generic").source().orElseThrow());
        assertEquals(2, result.schema().entries().size(), "no synthetic entry created for a type-parameter application in source");
    }

    /** The same body under a declared parameter is a template, which is exactly what may route one. */
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
     * A pure alias cycle -- an {@code a} is a {@code b} is an {@code a} -- has no base case, so nothing can
     * ever be one. It used to link cleanly, with this fixture asserting only that the variant-flattening walk
     * terminated rather than hanging; the diagnostic it called "separate and unimplemented" now exists
     * (§5.10.1). Termination is still what the walk needs and is still proved here --
     * by a verdict arriving at all.
     */
    @Test
    void aReferenceCycleAmongVariantsIsUninhabited() {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("a", TypeDefinition.reference("b"));
        entries.put("b", TypeDefinition.reference("a"));
        entries.put("contact", choiceEntry(new ChoiceBody(List.of(TypeRef.of("a"), TypeRef.of("b")))));

        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> TsonSchemaLinker.link(schemaOf(entries), null));

        assertTrue(thrown.getMessage().contains("can never be satisfied"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("a needs b"), thrown.getMessage());
    }

    /** An IS-A pair: `positive` is an `integer`, so no value of one excludes the other (§5.4's own example). */
    private static Map<String, TypeDefinition> nonDisjointChoice() {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("integer", new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), false,
                List.of(), List.of(), Optional.empty(), new IntegerType(new IntegerSize(32, true))));
        entries.put("positive", new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), false,
                List.of("integer"), List.of(), Optional.empty(), new IntegerType(new IntegerSize(32, true))));
        entries.put("contact", choiceEntry(
                new ChoiceBody(List.of(TypeRef.of("positive"), TypeRef.of("integer")))));
        return entries;
    }

    private static Annotations disjointMarker() {
        return Annotations.of(List.of(Annotation.of("disjoint")));
    }

    /**
     * §5.4's `@disjoint` assertion refuted: the author asserts disjointness the resolver can disprove.
     * Written after {@code =>}, so the marker rides on the definition.
     */
    @Test
    void rejectsADisjointAssertionTheDerivedFactRefutes() {
        Map<String, TypeDefinition> entries = nonDisjointChoice();
        entries.computeIfPresent("contact", (n, def) -> def.withAnnotations(disjointMarker()));

        TsonSchemaValidationException ex = assertThrows(TsonSchemaValidationException.class,
                () -> TsonSchemaLinker.link(schemaOf(entries), null));
        assertTrue(ex.getMessage().contains("are not disjoint"), ex.getMessage());
    }

    /** §6 lets the same marker precede the name, where it lands on the map key -- equally an assertion. */
    @Test
    void rejectsADisjointAssertionWrittenBeforeTheName() {
        AnnotatedMap<String, TypeDefinition> entries = AnnotatedMap.of(nonDisjointChoice());
        entries = entries.withAnnotations("contact", disjointMarker());

        TsonSchema schema = new TsonSchema("https://example.test/s.tn", TsonBundledSchemas.META_KERNEL_ID,
                List.of(), entries, false);
        assertThrows(TsonSchemaValidationException.class, () -> TsonSchemaLinker.link(schema, null));
    }

    /** The same choice without the assertion is legal: §5.4 permits a non-disjoint choice, it only needs the tag. */
    @Test
    void acceptsANonDisjointChoiceThatAssertsNothing() {
        TsonLinkedSchema linked = TsonSchemaLinker.link(schemaOf(nonDisjointChoice()), null);

        assertEquals(Optional.of(false), linked.schema().entries().get("contact").disjoint());
    }

    /** A verified assertion is silent -- distinct discrimination classes, so the derivation returns true. */
    @Test
    void acceptsADisjointAssertionTheDerivedFactProves() {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("label", new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), false,
                List.of(), List.of(), Optional.empty(), TextType.UNCONSTRAINED));
        entries.put("count", new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), false,
                List.of(), List.of(), Optional.empty(), new IntegerType(new IntegerSize(32, true))));
        entries.put("contact", choiceEntry(new ChoiceBody(List.of(TypeRef.of("label"), TypeRef.of("count"))))
                .withAnnotations(disjointMarker()));

        TsonLinkedSchema linked = TsonSchemaLinker.link(schemaOf(entries), null); // no exception
        assertEquals(Optional.of(true), linked.schema().entries().get("contact").disjoint());
    }

    /**
     * §5.4's derivation is two-valued and class-based, and MUST NOT prove more, so an assertion a value-set
     * reading might have called merely unprovable is simply refuted here: {@code even} and {@code small}
     * are both number-class, and no encoding's single form-resolution pass separates a same-class pair,
     * however their value sets relate.
     */
    @Test
    void rejectsADisjointAssertionOnSameClassVariants() {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("integer", new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), false,
                List.of(), List.of(), Optional.empty(), IntegerType.UNCONSTRAINED));
        entries.put("even", integerEntry(new IntegerType(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(BigInteger.TWO))));
        entries.put("small", integerEntry(new IntegerType(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(BigInteger.TEN), Optional.empty(), Optional.empty())));
        entries.put("contact", choiceEntry(new ChoiceBody(List.of(TypeRef.of("even"), TypeRef.of("small"))))
                .withAnnotations(disjointMarker()));

        TsonSchemaValidationException ex = assertThrows(TsonSchemaValidationException.class,
                () -> TsonSchemaLinker.link(schemaOf(entries), null));
        assertTrue(ex.getMessage().contains("are not disjoint"), ex.getMessage());
    }

    /** The same choice without the assertion is legal -- §5.4 only ever reports the assertion. */
    @Test
    void acceptsASameClassChoiceThatAssertsNothing() {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("integer", new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), false,
                List.of(), List.of(), Optional.empty(), IntegerType.UNCONSTRAINED));
        entries.put("even", integerEntry(new IntegerType(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(BigInteger.TWO))));
        entries.put("small", integerEntry(new IntegerType(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(BigInteger.TEN), Optional.empty(), Optional.empty())));
        entries.put("contact", choiceEntry(new ChoiceBody(List.of(TypeRef.of("even"), TypeRef.of("small")))));

        TsonLinkedSchema linked = TsonSchemaLinker.link(schemaOf(entries), null); // no exception
        assertEquals(Optional.of(false), linked.schema().entries().get("contact").disjoint());
    }

    /** §5.4: a variant MUST NOT resolve to {@code void} -- {@code (T | void)} spells optionality as a choice. */
    @Test
    void rejectsAVoidChoiceVariant() {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("void", unitEntry());
        entries.put("label", new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), false,
                List.of(), List.of(), Optional.empty(), TextType.UNCONSTRAINED));
        entries.put("maybe_label", choiceEntry(
                new ChoiceBody(List.of(TypeRef.of("label"), TypeRef.of("void")))));

        TsonSchemaValidationException ex = assertThrows(TsonSchemaValidationException.class,
                () -> TsonSchemaLinker.link(schemaOf(entries), null));
        assertTrue(ex.getMessage().contains("optionality is not choice"), ex.getMessage());
    }

    /** Judged after §8.3 flattening, so an alias of {@code void} is caught under the author's own name. */
    @Test
    void rejectsAVoidVariantReachedThroughAnAlias() {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("void", unitEntry());
        entries.put("nothing", TypeDefinition.reference("void"));
        entries.put("label", new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), false,
                List.of(), List.of(), Optional.empty(), TextType.UNCONSTRAINED));
        entries.put("maybe_label", choiceEntry(
                new ChoiceBody(List.of(TypeRef.of("label"), TypeRef.of("nothing")))));

        TsonSchemaValidationException ex = assertThrows(TsonSchemaValidationException.class,
                () -> TsonSchemaLinker.link(schemaOf(entries), null));
        assertTrue(ex.getMessage().contains("'nothing'"), ex.getMessage());
    }

    private static TypeDefinition integerEntry(IntegerType body) {
        return new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), false,
                List.of("integer"), List.of(), Optional.empty(), body);
    }

    /**
     * The subtypes pass rebuilds every entry that gains one, and a rebuild that forgets a component blanks
     * it for the whole schema. {@code position} locates every diagnostic reported against the entry and
     * {@code annotations} carries §6 metadata written after {@code =>} -- neither is recoverable afterwards,
     * and nothing else would notice them missing.
     */
    @Test
    void anEntryGainingSubtypesKeepsItsPositionAndAnnotations() {
        Annotations doc = Annotations.of(List.of(Annotation.of("doc", "the parent")));
        Optional<SourcePosition> position = Optional.of(new Position(7, 3, 42));
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("parent", emptyRecord().withPosition(position).withAnnotations(doc));
        entries.put("child", new TypeDefinition(Optional.empty(), TypeKind.PRODUCT, List.of(), false,
                List.of("parent"), List.of(), Optional.empty(), RecordBody.of(List.of())));

        TypeDefinition parent = TsonSchemaLinker.link(schemaOf(entries), null).schema().entries().get("parent");

        assertEquals(List.of("child"), parent.subtypes());
        assertEquals(position, parent.position());
        assertEquals(doc, parent.annotations());
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
                Map.of(TsonCanonicalIdentity.canonicalize("https://example.test/import.tn"), imported);
        TsonSchemaLoader loader = id -> Optional.ofNullable(byIdentity.get(id));

        Map<String, TypeDefinition> localEntries = new LinkedHashMap<>();
        localEntries.put("local_a", TypeDefinition.product(
                RecordBody.of(List.of(RecordField.required("field", TypeRef.of("imported_a"))))));
        TsonSchema local = new TsonSchema("https://example.test/importer.tn",
                "https://example.test/meta.tn", List.of("https://example.test/import.tn"), localEntries);

        TsonLinkedSchema result = TsonSchemaLinker.link(local, loader);

        assertEquals(Set.of("imported_a", "local_a"), result.schema().entries().keySet());
    }

    @Test
    void rejectsAnImportThatIsNotRegistered() {
        TsonSchemaLoader loader = id -> Optional.empty();
        TsonSchema local = new TsonSchema("https://example.test/importer.tn",
                "https://example.test/meta.tn", List.of("https://example.test/missing.tn"), Map.of());

        assertThrows(TsonSchemaValidationException.class, () -> TsonSchemaLinker.link(local, loader));
    }

    @Test
    void rejectsACollisionBetweenALocalEntryAndAnImportedEntry() {
        // A genuinely different type under the same name. This used to declare `emptyRecord()` on both
        // sides, which is not a collision at all: [TSON-SCHEMA] §8.2 makes two entries that are the same
        // entry one entry, so the assertion passed only because nothing checked whether they agreed.
        assertThrows(TsonSchemaValidationException.class, () -> link("shared_name", emptyRecord(), unitEntry()));
    }

    /**
     * And the other half of the same rule: an entry a local declaration and an import both reach, agreeing
     * byte for byte, unifies rather than colliding. §8.2 names a materialised instantiation by a function of
     * its resolved form alone -- "two {@code box<text>} anywhere share one entry" -- so a consumer that
     * closes an application its import already closed must link, or exporting a template is exporting a trap.
     */
    @Test
    void anImportedEntryAndAnIdenticalLocalOneUnify() {
        assertDoesNotThrow(() -> link("shared_name", emptyRecord(), emptyRecord()));
    }

    /** Links a schema declaring {@code name} locally against an import declaring the same name. */
    private static TsonLinkedSchema link(String name, TypeDefinition importedEntry, TypeDefinition localEntry) {
        TsonLinkedSchema imported = new TsonLinkedSchema(schemaOf(Map.of(name, importedEntry)));
        Map<String, TsonLinkedSchema> byIdentity =
                Map.of(TsonCanonicalIdentity.canonicalize("https://example.test/import.tn"), imported);
        TsonSchemaLoader loader = id -> Optional.ofNullable(byIdentity.get(id));

        TsonSchema local = new TsonSchema("https://example.test/importer.tn",
                "https://example.test/meta.tn", List.of("https://example.test/import.tn"),
                Map.of(name, localEntry));
        return TsonSchemaLinker.link(local, loader);
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
                "https://example.test/meta.tn", List.of(), Map.of("uuid", unitEntry())));
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
                TsonCanonicalIdentity.canonicalize("https://example.test/import.tn"), new TsonLinkedSchema(imported));
        TsonSchemaLoader loader = id -> Optional.ofNullable(byIdentity.get(id));

        Map<String, TypeDefinition> localEntries = new LinkedHashMap<>();
        localEntries.put("local_child", new TypeDefinition(Optional.empty(), TypeKind.PRODUCT, List.of(), false,
                List.of("imported_base"), List.of(), Optional.empty(), RecordBody.of(List.of())));
        TsonSchema local = new TsonSchema("https://example.test/importer.tn",
                "https://example.test/meta.tn", List.of("https://example.test/import.tn"), localEntries);

        TsonLinkedSchema result = TsonSchemaLinker.link(local, loader);

        // This importer's own merged view: the pre-existing subtype plus the newly-local one.
        assertEquals(Set.of("imported_child", "local_child"),
                Set.copyOf(result.schema().entries().get("imported_base").subtypes()));

        // The originally-registered "imported" schema itself is untouched -- it never learns of local_child.
        assertEquals(Set.of("imported_child"), Set.copyOf(imported.entries().get("imported_base").subtypes()));
    }

    @Test
    void rejectsACollisionBetweenTwoImports() {
        // Two genuinely *different* schemas -- distinct !!ids -- each declaring the name. That is the real
        // collision: one name cannot denote two types in a flat namespace, so it is still rejected under the
        // identity-based rule (§2.2.3). The ids have to differ for this to be the case under
        // test at all; two copies claiming one id are one schema reached twice, which unifies (below).
        TsonLinkedSchema importedOne = new TsonLinkedSchema(
                schemaOf("https://example.test/import-one.tn", List.of(), Map.of("shared_name", emptyRecord())),
                Map.of("shared_name", TsonCanonicalIdentity.canonicalize("https://example.test/import-one.tn")));
        TsonLinkedSchema importedTwo = new TsonLinkedSchema(
                schemaOf("https://example.test/import-two.tn", List.of(), Map.of("shared_name", emptyRecord())),
                Map.of("shared_name", TsonCanonicalIdentity.canonicalize("https://example.test/import-two.tn")));
        Map<String, TsonLinkedSchema> byIdentity = Map.of(
                TsonCanonicalIdentity.canonicalize("https://example.test/import-one.tn"), importedOne,
                TsonCanonicalIdentity.canonicalize("https://example.test/import-two.tn"), importedTwo);
        TsonSchemaLoader loader = id -> Optional.ofNullable(byIdentity.get(id));

        TsonSchema local = new TsonSchema("https://example.test/importer.tn",
                "https://example.test/meta.tn",
                List.of("https://example.test/import-one.tn", "https://example.test/import-two.tn"), Map.of());

        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> TsonSchemaLinker.link(local, loader));
        assertTrue(thrown.getMessage().contains("shared_name"), thrown::getMessage);
        assertTrue(thrown.getMessage().contains("import-one.tn")
                && thrown.getMessage().contains("import-two.tn"), thrown::getMessage);
    }

    /**
     * The other side of the identity rule: <b>one</b> schema reached by two routes unifies. {@code b.tn}
     * imports {@code shared.tn} and the importer names both, so every one of {@code shared.tn}'s entries
     * arrives twice -- the diamond every schema importing core.tn forms. A name-occurrence rule rejects it;
     * an identity rule sees one set of entries (§2.2.3).
     */
    @Test
    void unifiesOneSchemaReachedThroughTwoImportRoutes() {
        TsonSchema shared = schemaOf("https://example.test/shared.tn", List.of(),
                Map.of("shared_name", emptyRecord()));
        // The origins map link() really produces: every entry recorded under the canonical identity of the
        // schema that declared it, its own locals included. The one-arg constructor would leave it empty and
        // fall back to the raw !!id, which is not what a registered schema ever looks like.
        TsonLinkedSchema sharedLinked = new TsonLinkedSchema(shared,
                Map.of("shared_name", TsonCanonicalIdentity.canonicalize("https://example.test/shared.tn")));
        TsonLinkedSchema viaB = new TsonLinkedSchema(
                schemaOf("https://example.test/b.tn", List.of("https://example.test/shared.tn"),
                        Map.of("shared_name", emptyRecord(), "b_type", emptyRecord())),
                Map.of("shared_name", TsonCanonicalIdentity.canonicalize("https://example.test/shared.tn")));
        Map<String, TsonLinkedSchema> byIdentity = Map.of(
                TsonCanonicalIdentity.canonicalize("https://example.test/shared.tn"), sharedLinked,
                TsonCanonicalIdentity.canonicalize("https://example.test/b.tn"), viaB);
        TsonSchemaLoader loader = id -> Optional.ofNullable(byIdentity.get(id));

        TsonSchema local = new TsonSchema("https://example.test/importer.tn",
                "https://example.test/meta.tn",
                List.of("https://example.test/shared.tn", "https://example.test/b.tn"), Map.of());

        TsonLinkedSchema linked = TsonSchemaLinker.link(local, loader);

        assertTrue(linked.schema().entries().containsKey("shared_name"));
        assertTrue(linked.schema().entries().containsKey("b_type"));
        // The declaring schema, not the intermediary that passed it along.
        assertEquals(TsonCanonicalIdentity.canonicalize("https://example.test/shared.tn"),
                linked.originOf("shared_name"));
    }
}
