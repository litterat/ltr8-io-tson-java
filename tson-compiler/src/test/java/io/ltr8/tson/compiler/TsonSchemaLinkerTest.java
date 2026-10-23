package io.ltr8.tson.compiler;

import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.schema.*;
import io.ltr8.annotation.Annotation;
import io.ltr8.annotation.AnnotatedMap;
import io.ltr8.annotation.Annotations;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.FieldGroup;
import io.ltr8.tson.schema.meta.IntegerSize;
import io.ltr8.tson.schema.meta.IntegerType;
import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.base.SourcePosition;
import io.ltr8.tson.schema.meta.TextType;
import io.ltr8.tson.schema.meta.TypeArgument;
import io.ltr8.tson.schema.meta.TemplateParam;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.ValueType;
import io.ltr8.tson.schema.meta.VoidType;
import io.ltr8.tson.schema.meta.Type;
import io.ltr8.tson.schema.meta.TypeChain;

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

    private static Top valueEntry() {
        return new ValueType();
    }

    private static Top voidEntry() {
        return new VoidType();
    }

    private static Top emptyRecord() {
        return RecordBody.of(RecordBody.fieldMap());
    }

    /** A declared choice -- what {@code (A | B)} resolves to. {@code disjoint} is the linker's to derive. */
    private static Top choiceEntry(ChoiceBody body) {
        return body;
    }

    /**
     * {@code !!meta} is {@link TsonBundledSchemas#META_KERNEL_ID} itself -- every caller passes {@code
     * null} for {@code loader}, so the structure namespace this would otherwise supply is always
     * empty regardless of what URI it names; using the real meta-kernel identity instead lets a
     * hand-built local entry declare its own {@code constructor: true} vocabulary without tripping
     * {@code TsonSchemaLinker}'s own "only the meta-kernel may declare constructors" check, which
     * compares against that exact identity, not merely "is this schema self-referencing."
     */
    private static TsonSchema schemaOf(Map<String, Top> entries) {
        return new TsonSchema("https://example.test/s.tn", TsonBundledSchemas.META_KERNEL_ID,
                List.of(), entries);
    }

    /** The same, each entry's facts -- its position, its value's annotations -- given. */
    private static TsonSchema schemaOf(Map<String, Top> entries, Map<String, TsonSchema.EntryFacts> facts) {
        return new TsonSchema("https://example.test/s.tn", TsonBundledSchemas.META_KERNEL_ID,
                List.of(), AnnotatedMap.of(entries), false, Map.of(), facts);
    }

    /** {@code @disjoint} written after {@code =>}, on {@code name}'s value -- the entry's facts. */
    private static Map<String, TsonSchema.EntryFacts> disjointOn(String name) {
        return Map.of(name, TsonSchema.EntryFacts.NONE.withAnnotations(disjointMarker()));
    }

    /** A schema with its own identity and imports, for the origin-tracking chain below. */
    private static TsonSchema schemaOf(String id, List<String> imports, Map<String, Top> entries) {
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
        Map<String, Top> entries = new LinkedHashMap<>();
        entries.put("token", valueEntry());
        entries.put("set", emptyRecord());
        entries.put("container", RecordBody.of(RecordBody.fieldMap(Map.entry("members",
                RecordField.required(new TypeRef("set", List.of(new TypeArgument.Ref(TypeRef.of("token")))))))));

        SchemaValidationException thrown = assertThrows(SchemaValidationException.class,
                () -> TsonSchemaLinker.link(schemaOf(entries), null));
        assertTrue(thrown.getMessage().contains("'set' declares no type parameters"), thrown.getMessage());
    }

    /** A one-parameter record template: the record it is, with its parameter stated ([TSON-SCHEMA] §5.10). */
    private static Top recordTemplate(String parameter, Namespace<RecordField> fields) {
        return RecordBody.of(fields).withParameters(List.of(TemplateParam.typeParameter(parameter)));
    }

    /**
     * An argument-bearing type-ref reaches the linker only from a parameterized declaration's own body --
     * {@code SchemaDesugarer} turns every other one into a real declaration before resolution ever runs, so
     * there is nothing here to synthesize an entry for. This pins that: link carries the application through
     * untouched and adds no entries at all. What the linker therefore does <em>not</em> do -- building the
     * {@code ArrayBody}/placeholder entries a materialisation pass would -- is covered in {@code
     * SchemaDesugarerTest}, one phase earlier.
     *
     * <p><b>The argument is a real entry, and it has to be.</b> An instantiation's key records the
     * application it closes, closed; a partial application states the arguments it binds in its own {@code
     * reference.target} (§8.1's alias paragraph). Neither puts a parameter here.
     */
    @Test
    void anArgumentBearingApplicationIsCarriedThroughWithoutMaterialising() {
        Map<String, Top> entries = new LinkedHashMap<>();
        entries.put("token", valueEntry());
        entries.put("other",
                recordTemplate("U", RecordBody.fieldMap(Map.entry("v", RecordField.required(TypeRef.of("U"))))));
        TypeRef otherOfToken = new TypeRef("other", List.of(new TypeArgument.Ref(TypeRef.of("token"))));
        entries.put("generic", RecordBody.of(RecordBody.fieldMap()));

        AnnotatedMap<String, Top> annotated = AnnotatedMap.of(entries)
                .withAnnotations("generic", TsonSchema.withApplication(io.ltr8.annotation.Annotations.empty(),
                        otherOfToken));
        TsonLinkedSchema result = TsonSchemaLinker.link(new TsonSchema("https://example.test/s.tn",
                TsonBundledSchemas.META_KERNEL_ID, List.of(), annotated, false, Map.of(), Map.of()), null);

        assertEquals(otherOfToken, result.schema().applicationOf("generic").orElseThrow());
        assertEquals(3, result.schema().entries().size(),
                "no synthetic entry created for an application an instantiation records");
    }

    /** The same body under a declared parameter is a template, which is exactly what may route one. */
    @Test
    void rejectsAFieldReferencingAnUndeclaredType() {
        Map<String, Top> entries = new LinkedHashMap<>();
        entries.put("container", RecordBody.of(RecordBody.fieldMap(
                Map.entry("field", RecordField.required(TypeRef.of("no_such_type"))))));

        SchemaValidationException ex = assertThrows(SchemaValidationException.class,
                () -> TsonSchemaLinker.link(schemaOf(entries), null));
        assertTrue(ex.getMessage().contains("no_such_type"));
    }

    /** §5.4's distinct-variant rule at its plainest: the same name written twice. */
    @Test
    void rejectsAChoiceListingOneVariantTwice() {
        Map<String, Top> entries = new LinkedHashMap<>();
        entries.put("token", valueEntry());
        entries.put("contact", choiceEntry(new ChoiceBody(List.of(TypeRef.of("token"), TypeRef.of("token")))));

        SchemaValidationException ex = assertThrows(SchemaValidationException.class,
                () -> TsonSchemaLinker.link(schemaOf(entries), null));
        assertTrue(ex.getMessage().contains("lists the variant 'token' twice"), ex.getMessage());
    }

    /**
     * The case the rule exists for: §8.3 makes an alias and its target one type, so two spellings of it are
     * a duplicate an author cannot see. The diagnostic names both spellings and what they landed on.
     */
    @Test
    void rejectsTwoVariantsThatFlattenToOneType() {
        Map<String, Top> entries = new LinkedHashMap<>();
        entries.put("token", valueEntry());
        entries.put("nickname", new io.ltr8.tson.schema.meta.Reference(io.ltr8.tson.schema.meta.TypeRef.of("token")));
        entries.put("contact", choiceEntry(
                new ChoiceBody(List.of(TypeRef.of("token"), TypeRef.of("nickname")))));

        SchemaValidationException ex = assertThrows(SchemaValidationException.class,
                () -> TsonSchemaLinker.link(schemaOf(entries), null));
        assertTrue(ex.getMessage().contains("'token' and 'nickname' both resolve to 'token'"), ex.getMessage());
    }

    /** Two aliases of *different* types stay distinct -- flattening must not collapse what it merely renames. */
    @Test
    void acceptsAliasedVariantsThatFlattenToDifferentTypes() {
        Map<String, Top> entries = new LinkedHashMap<>();
        entries.put("token", valueEntry());
        entries.put("other", emptyRecord());
        entries.put("nickname", new io.ltr8.tson.schema.meta.Reference(io.ltr8.tson.schema.meta.TypeRef.of("token")));
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
        Map<String, Top> entries = new LinkedHashMap<>();
        entries.put("a", new io.ltr8.tson.schema.meta.Reference(io.ltr8.tson.schema.meta.TypeRef.of("b")));
        entries.put("b", new io.ltr8.tson.schema.meta.Reference(io.ltr8.tson.schema.meta.TypeRef.of("a")));
        entries.put("contact", choiceEntry(new ChoiceBody(List.of(TypeRef.of("a"), TypeRef.of("b")))));

        SchemaValidationException thrown = assertThrows(SchemaValidationException.class,
                () -> TsonSchemaLinker.link(schemaOf(entries), null));

        assertTrue(thrown.getMessage().contains("can never be satisfied"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("a needs b"), thrown.getMessage());
    }

    /** An IS-A pair: `positive` is an `integer`, so no value of one excludes the other (§5.4's own example). */
    private static Map<String, Top> nonDisjointChoice() {
        Map<String, Top> entries = new LinkedHashMap<>();
        entries.put("integer", new IntegerType(new IntegerSize(32, true)));
        entries.put("positive", ((Type) (new IntegerType(new IntegerSize(32,
                true)))).withSupertypes(List.of("integer")));
        entries.put("contact", choiceEntry(
                new ChoiceBody(List.of(TypeRef.of("positive"), TypeRef.of("integer")))));
        return entries;
    }

    private static Annotations disjointMarker() {
        return Annotations.of(List.of(Annotation.of("disjoint")));
    }

    /**
     * §5.4's `@disjoint` assertion refuted: the author asserts disjointness the resolver can disprove.
     * Written after {@code =>}, so the marker rides on the value, among the entry's facts.
     */
    @Test
    void rejectsADisjointAssertionTheDerivedFactRefutes() {
        Map<String, Top> entries = nonDisjointChoice();

        SchemaValidationException ex = assertThrows(SchemaValidationException.class,
                () -> TsonSchemaLinker.link(schemaOf(entries, disjointOn("contact")), null));
        assertTrue(ex.getMessage().contains("are not disjoint"), ex.getMessage());
    }

    /** §6 lets the same marker precede the name, where it lands on the map key -- equally an assertion. */
    @Test
    void rejectsADisjointAssertionWrittenBeforeTheName() {
        AnnotatedMap<String, Top> entries = AnnotatedMap.of(nonDisjointChoice());
        entries = entries.withAnnotations("contact", disjointMarker());

        TsonSchema schema = new TsonSchema("https://example.test/s.tn", TsonBundledSchemas.META_KERNEL_ID,
                List.of(), entries, false);
        assertThrows(SchemaValidationException.class, () -> TsonSchemaLinker.link(schema, null));
    }

    /** The same choice without the assertion is legal: §5.4 permits a non-disjoint choice, it only needs the tag. */
    @Test
    void acceptsANonDisjointChoiceThatAssertsNothing() {
        TsonLinkedSchema linked = TsonSchemaLinker.link(schemaOf(nonDisjointChoice()), null);

        assertEquals(Optional.of(false), ((ChoiceBody) linked.schema().entries().get("contact")).disjoint());
    }

    /** A verified assertion is silent -- distinct discrimination classes, so the derivation returns true. */
    @Test
    void acceptsADisjointAssertionTheDerivedFactProves() {
        Map<String, Top> entries = new LinkedHashMap<>();
        entries.put("label", TextType.UNCONSTRAINED);
        entries.put("count", new IntegerType(new IntegerSize(32, true)));
        entries.put("contact", choiceEntry(new ChoiceBody(List.of(TypeRef.of("label"), TypeRef.of("count")))));

        TsonLinkedSchema linked = TsonSchemaLinker.link(schemaOf(entries, disjointOn("contact")), null);
        assertEquals(Optional.of(true), ((ChoiceBody) linked.schema().entries().get("contact")).disjoint());
    }

    /**
     * §5.4's derivation is two-valued and class-based, and MUST NOT prove more, so an assertion a value-set
     * reading might have called merely unprovable is simply refuted here: {@code even} and {@code small}
     * are both number-class, and no encoding's single form-resolution pass separates a same-class pair,
     * however their value sets relate.
     */
    @Test
    void rejectsADisjointAssertionOnSameClassVariants() {
        Map<String, Top> entries = new LinkedHashMap<>();
        entries.put("integer", IntegerType.UNCONSTRAINED);
        entries.put("even", integerEntry(new IntegerType(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(BigInteger.TWO))));
        entries.put("small", integerEntry(new IntegerType(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(BigInteger.TEN), Optional.empty(), Optional.empty())));
        entries.put("contact", choiceEntry(new ChoiceBody(List.of(TypeRef.of("even"), TypeRef.of("small")))));

        SchemaValidationException ex = assertThrows(SchemaValidationException.class,
                () -> TsonSchemaLinker.link(schemaOf(entries, disjointOn("contact")), null));
        assertTrue(ex.getMessage().contains("are not disjoint"), ex.getMessage());
    }

    /** The same choice without the assertion is legal -- §5.4 only ever reports the assertion. */
    @Test
    void acceptsASameClassChoiceThatAssertsNothing() {
        Map<String, Top> entries = new LinkedHashMap<>();
        entries.put("integer", IntegerType.UNCONSTRAINED);
        entries.put("even", integerEntry(new IntegerType(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(BigInteger.TWO))));
        entries.put("small", integerEntry(new IntegerType(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(BigInteger.TEN), Optional.empty(), Optional.empty())));
        entries.put("contact", choiceEntry(new ChoiceBody(List.of(TypeRef.of("even"), TypeRef.of("small")))));

        TsonLinkedSchema linked = TsonSchemaLinker.link(schemaOf(entries), null); // no exception
        assertEquals(Optional.of(false), ((ChoiceBody) linked.schema().entries().get("contact")).disjoint());
    }

    /** §5.4: a variant MUST NOT resolve to {@code void} -- {@code (T | void)} spells optionality as a choice. */
    @Test
    void rejectsAVoidChoiceVariant() {
        Map<String, Top> entries = new LinkedHashMap<>();
        entries.put("void", voidEntry());
        entries.put("label", TextType.UNCONSTRAINED);
        entries.put("maybe_label", choiceEntry(
                new ChoiceBody(List.of(TypeRef.of("label"), TypeRef.of("void")))));

        SchemaValidationException ex = assertThrows(SchemaValidationException.class,
                () -> TsonSchemaLinker.link(schemaOf(entries), null));
        assertTrue(ex.getMessage().contains("optionality is not choice"), ex.getMessage());
    }

    /** Judged after §8.3 flattening, so an alias of {@code void} is caught under the author's own name. */
    @Test
    void rejectsAVoidVariantReachedThroughAnAlias() {
        Map<String, Top> entries = new LinkedHashMap<>();
        entries.put("void", voidEntry());
        entries.put("nothing", new io.ltr8.tson.schema.meta.Reference(io.ltr8.tson.schema.meta.TypeRef.of("void")));
        entries.put("label", TextType.UNCONSTRAINED);
        entries.put("maybe_label", choiceEntry(
                new ChoiceBody(List.of(TypeRef.of("label"), TypeRef.of("nothing")))));

        SchemaValidationException ex = assertThrows(SchemaValidationException.class,
                () -> TsonSchemaLinker.link(schemaOf(entries), null));
        assertTrue(ex.getMessage().contains("'nothing'"), ex.getMessage());
    }

    private static Top integerEntry(IntegerType body) {
        return ((Type) (body)).withSupertypes(List.of("integer"));
    }

    /**
     * The subtypes pass rebuilds every entry that gains one, and a linked schema that dropped an entry's facts
     * would blank them for the whole schema. {@code position} locates every diagnostic reported against the
     * entry and {@code annotations} carries §6 metadata written after {@code =>} -- neither is recoverable
     * afterwards, and nothing else would notice them missing.
     */
    @Test
    void anEntryGainingSubtypesKeepsItsPositionAndAnnotations() {
        Annotations doc = Annotations.of(List.of(Annotation.of("doc", "the parent")));
        Optional<SourcePosition> position = Optional.of(new Position(7, 3, 42));
        Map<String, Top> entries = new LinkedHashMap<>();
        entries.put("parent", emptyRecord());
        entries.put("child",
                ((Type) (RecordBody.of(RecordBody.fieldMap()))).withSupertypes(List.of("parent")));

        TsonSchema linked = TsonSchemaLinker.link(schemaOf(entries,
                Map.of("parent", TsonSchema.EntryFacts.at(position).withAnnotations(doc))), null).schema();

        assertEquals(List.of("child"), TypeChain.subtypesOf(linked.entries().get("parent")));
        assertEquals(position, linked.positionOf("parent"));
        assertEquals(doc, linked.factsOf("parent").annotations());
    }

    @Test
    void rejectsAnUnresolvedSupertype() {
        Map<String, Top> entries = new LinkedHashMap<>();
        entries.put("child",
                ((Type) (RecordBody.of(RecordBody.fieldMap()))).withSupertypes(List.of("no_such_supertype")));

        assertThrows(SchemaValidationException.class, () -> TsonSchemaLinker.link(schemaOf(entries), null));
    }

    @Test
    void rejectsAFieldGroupReferencingAnUnknownField() {
        Map<String, Top> entries = new LinkedHashMap<>();
        entries.put("thing", new RecordBody(
                RecordBody.fieldMap(Map.entry("a", RecordField.required(TypeRef.of("thing")))),
                List.of(FieldGroup.ofSingles(List.of("not_a_real_field"), true)),
                io.ltr8.tson.schema.meta.RecordExtensionType.OPEN));

        assertThrows(SchemaValidationException.class, () -> TsonSchemaLinker.link(schemaOf(entries), null));
    }

    @Test
    void mergesImportedEntriesBeforeLocalOnesAndValidatesTheWhole() {
        TsonLinkedSchema imported = new TsonLinkedSchema(schemaOf(Map.of("imported_a", emptyRecord())));
        Map<String, TsonLinkedSchema> byIdentity =
                Map.of(CanonicalIdentity.canonicalize("https://example.test/import.tn"), imported);
        TsonSchemaLoader loader = id -> Optional.ofNullable(byIdentity.get(id));

        Map<String, Top> localEntries = new LinkedHashMap<>();
        localEntries.put("local_a", RecordBody.of(RecordBody.fieldMap(Map.entry("field",
                RecordField.required(TypeRef.of("imported_a"))))));
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

        assertThrows(SchemaValidationException.class, () -> TsonSchemaLinker.link(local, loader));
    }

    @Test
    void rejectsACollisionBetweenALocalEntryAndAnImportedEntry() {
        // A genuinely different type under the same name. This used to declare `emptyRecord()` on both
        // sides, which is not a collision at all: [TSON-SCHEMA] §8.2 makes two entries that are the same
        // entry one entry, so the assertion passed only because nothing checked whether they agreed.
        assertThrows(SchemaValidationException.class, () -> link("shared_name", emptyRecord(), valueEntry()));
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
    private static TsonLinkedSchema link(String name, Top importedEntry, Top localEntry) {
        TsonLinkedSchema imported = new TsonLinkedSchema(schemaOf(Map.of(name, importedEntry)));
        Map<String, TsonLinkedSchema> byIdentity =
                Map.of(CanonicalIdentity.canonicalize("https://example.test/import.tn"), imported);
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
                "https://example.test/meta.tn", List.of(), Map.of("uuid", valueEntry())));
        Map<String, TsonLinkedSchema> byIdentity =
                Map.of(CanonicalIdentity.canonicalize("https://example.test/lib.tn"), library);
        TsonSchemaLoader loader = id -> Optional.ofNullable(byIdentity.get(id));

        TsonSchema governed = new TsonSchema("https://example.test/app.tn",
                "https://example.test/lib.tn", List.of(), Map.of("local", emptyRecord()));

        SchemaValidationException thrown = assertThrows(SchemaValidationException.class,
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
                Map.of(CanonicalIdentity.canonicalize("https://example.test/meta.tn"), meta);
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
        Map<String, Top> entries = new LinkedHashMap<>();
        entries.put("response", RecordBody.of(RecordBody.fieldMap(
                Map.entry("status", RecordField.required(TypeRef.of("token"))))));
        entries.put("token", valueEntry());
        entries.put("success_response", ((Type) (RecordBody.of(RecordBody.fieldMap(Map.entry("data",
                RecordField.required(TypeRef.of("token"))))))).withSupertypes(List.of("response")));
        entries.put("failure_response", ((Type) (RecordBody.of(RecordBody.fieldMap(Map.entry("error",
                RecordField.required(TypeRef.of("token"))))))).withSupertypes(List.of("response")));

        TsonLinkedSchema result = TsonSchemaLinker.link(schemaOf(entries), null);

        assertEquals(Set.of("success_response", "failure_response"),
                Set.copyOf(TypeChain.subtypesOf(result.schema().entries().get("response"))));
        assertTrue(TypeChain.subtypesOf(result.schema().entries().get("success_response")).isEmpty());
        assertTrue(TypeChain.subtypesOf(result.schema().entries().get("token")).isEmpty());
    }

    @Test
    void subtypesPropagateTransitivelyThroughAnAlreadyTransitiveSupertypeChain() {
        // supertypes is already the full transitive chain by the time this runs (DefinitionResolver's
        // own induction), so the reverse index falls out the same way with no extra closure step.
        Map<String, Top> entries = new LinkedHashMap<>();
        entries.put("top", emptyRecord());
        entries.put("mid",
                ((Type) (RecordBody.of(RecordBody.fieldMap()))).withSupertypes(List.of("top")));
        entries.put("leaf", ((Type) (RecordBody.of(RecordBody.fieldMap()))).withSupertypes(List.of("mid",
                "top")).withSubtypes(List.of()));

        TsonLinkedSchema result = TsonSchemaLinker.link(schemaOf(entries), null);

        assertEquals(Set.of("mid", "leaf"), Set.copyOf(TypeChain.subtypesOf(result.schema().entries().get("top"))));
        assertEquals(Set.of("leaf"), Set.copyOf(TypeChain.subtypesOf(result.schema().entries().get("mid"))));
    }

    @Test
    void importedSupertypesGainLocalSubtypesInThisSchemasOwnViewButTheOriginalRegistrationIsUntouched() {
        // imported_base already has "imported_child" as a subtype from its own home schema's
        // registration -- computeSubtypes must union with that, not replace it.
        Map<String, Top> importedEntries = new LinkedHashMap<>();
        importedEntries.put("imported_base",
                ((Type) (RecordBody.of(RecordBody.fieldMap()))).withSubtypes(List.of("imported_child")));
        importedEntries.put("imported_child",
                ((Type) (RecordBody.of(RecordBody.fieldMap()))).withSupertypes(List.of("imported_base")));
        TsonSchema imported = schemaOf(importedEntries);
        Map<String, TsonLinkedSchema> byIdentity = Map.of(
                CanonicalIdentity.canonicalize("https://example.test/import.tn"), new TsonLinkedSchema(imported));
        TsonSchemaLoader loader = id -> Optional.ofNullable(byIdentity.get(id));

        Map<String, Top> localEntries = new LinkedHashMap<>();
        localEntries.put("local_child",
                ((Type) (RecordBody.of(RecordBody.fieldMap()))).withSupertypes(List.of("imported_base")));
        TsonSchema local = new TsonSchema("https://example.test/importer.tn",
                "https://example.test/meta.tn", List.of("https://example.test/import.tn"), localEntries);

        TsonLinkedSchema result = TsonSchemaLinker.link(local, loader);

        // This importer's own merged view: the pre-existing subtype plus the newly-local one.
        assertEquals(Set.of("imported_child", "local_child"),
                Set.copyOf(TypeChain.subtypesOf(result.schema().entries().get("imported_base"))));

        // The originally-registered "imported" schema itself is untouched -- it never learns of local_child.
        assertEquals(Set.of("imported_child"), Set.copyOf(TypeChain.subtypesOf(imported.entries().get("imported_base"))));
    }

    @Test
    void rejectsACollisionBetweenTwoImports() {
        // Two genuinely *different* schemas -- distinct !!ids -- each declaring the name. That is the real
        // collision: one name cannot denote two types in a flat namespace, so it is still rejected under the
        // identity-based rule (§2.2.3). The ids have to differ for this to be the case under
        // test at all; two copies claiming one id are one schema reached twice, which unifies (below).
        TsonLinkedSchema importedOne = new TsonLinkedSchema(
                schemaOf("https://example.test/import-one.tn", List.of(), Map.of("shared_name", emptyRecord())),
                Map.of("shared_name", CanonicalIdentity.canonicalize("https://example.test/import-one.tn")));
        TsonLinkedSchema importedTwo = new TsonLinkedSchema(
                schemaOf("https://example.test/import-two.tn", List.of(), Map.of("shared_name", emptyRecord())),
                Map.of("shared_name", CanonicalIdentity.canonicalize("https://example.test/import-two.tn")));
        Map<String, TsonLinkedSchema> byIdentity = Map.of(
                CanonicalIdentity.canonicalize("https://example.test/import-one.tn"), importedOne,
                CanonicalIdentity.canonicalize("https://example.test/import-two.tn"), importedTwo);
        TsonSchemaLoader loader = id -> Optional.ofNullable(byIdentity.get(id));

        TsonSchema local = new TsonSchema("https://example.test/importer.tn",
                "https://example.test/meta.tn",
                List.of("https://example.test/import-one.tn", "https://example.test/import-two.tn"), Map.of());

        SchemaValidationException thrown = assertThrows(SchemaValidationException.class,
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
                Map.of("shared_name", CanonicalIdentity.canonicalize("https://example.test/shared.tn")));
        TsonLinkedSchema viaB = new TsonLinkedSchema(
                schemaOf("https://example.test/b.tn", List.of("https://example.test/shared.tn"),
                        Map.of("shared_name", emptyRecord(), "b_type", emptyRecord())),
                Map.of("shared_name", CanonicalIdentity.canonicalize("https://example.test/shared.tn")));
        Map<String, TsonLinkedSchema> byIdentity = Map.of(
                CanonicalIdentity.canonicalize("https://example.test/shared.tn"), sharedLinked,
                CanonicalIdentity.canonicalize("https://example.test/b.tn"), viaB);
        TsonSchemaLoader loader = id -> Optional.ofNullable(byIdentity.get(id));

        TsonSchema local = new TsonSchema("https://example.test/importer.tn",
                "https://example.test/meta.tn",
                List.of("https://example.test/shared.tn", "https://example.test/b.tn"), Map.of());

        TsonLinkedSchema linked = TsonSchemaLinker.link(local, loader);

        assertTrue(linked.schema().entries().containsKey("shared_name"));
        assertTrue(linked.schema().entries().containsKey("b_type"));
        // The declaring schema, not the intermediary that passed it along.
        assertEquals(CanonicalIdentity.canonicalize("https://example.test/shared.tn"),
                linked.originOf("shared_name"));
    }
}
