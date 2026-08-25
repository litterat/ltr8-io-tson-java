package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.TsonSchemaParser;
import io.ltr8.tson.compiler.ast.schema.FieldDef;
import io.ltr8.tson.compiler.ast.schema.GenericRef;
import io.ltr8.tson.compiler.ast.schema.Instance;
import io.ltr8.tson.compiler.ast.schema.InstanceTemplate;
import io.ltr8.tson.compiler.ast.schema.RecordDef;
import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.compiler.ast.schema.SchemaMap;
import io.ltr8.tson.compiler.ast.schema.SimpleRef;
import io.ltr8.tson.compiler.ast.schema.StructuralTypeDef;
import io.ltr8.tson.compiler.ast.schema.TypeArg;
import io.ltr8.tson.schema.TsonSchemaValidationException;
import io.ltr8.tson.schema.meta.SourcePosition;
import org.junit.jupiter.api.Test;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SchemaDesugarer}: the AST rewrite that turns every sugar form into the constructor application it
 * denotes before resolution, so {@code DefinitionResolver} only ever sees a bare reference or {@code !C
 * value}.
 *
 * <p>No governing meta appears in any fixture, and that is the point of the phase's current shape: the sugar
 * set is closed and grammar-supplied, so the head each form desugars to and the vocabulary field each
 * argument fills are a fixed table (§5.3), not something read off a constructor's parameter list.
 *
 * <p>Assertions on unchanged documents use {@link org.junit.jupiter.api.Assertions#assertSame}, not {@code
 * assertEquals}, deliberately. The nodes are records, so an equal-but-rebuilt tree would satisfy {@code
 * equals} while having silently dropped every entry in {@code TsonSchemaParser.declarationPositions()} -- an
 * {@code IdentityHashMap}, so a rebuilt {@code Declaration} no longer matches its own position. Reference
 * equality is what proves the structural sharing that keeps positions intact.
 */
class SchemaDesugarerTest {

    private static SchemaDocument parse(String declarations) {
        return new TsonSchemaParser("""
                !!meta:"https://tson.io/2026/33/m/meta-kernel.tn"
                {
                %s
                }
                """.formatted(declarations)).parseSchemaDocument();
    }

    private static SchemaDocument desugar(String declarations) {
        return desugar(declarations, Set.of());
    }

    private static SchemaDocument desugar(String declarations, Set<String> imported) {
        return SchemaDesugarer.desugar(parse(declarations), imported);
    }

    /** The injected declaration for the sole form in {@code document} whose derived name starts with {@code prefix}. */
    private static SchemaMap.Declaration onlyInjected(SchemaDocument document, String prefix) {
        List<SchemaMap.Declaration> matching = document.body().declarations().values().stream()
                .filter(d -> d.name().startsWith(prefix + "_")).toList();
        assertEquals(1, matching.size(), () -> "expected one injected " + prefix + " in "
                + document.body().declarations().keySet());
        return matching.get(0);
    }

    /** The type-ref of {@code declaration}'s first field, which every fixture here uses as the use site. */
    private static String firstFieldType(SchemaDocument document, String declaration) {
        StructuralTypeDef typeDef = (StructuralTypeDef) document.body().declarations().get(declaration).typeDef();
        FieldDef field = (FieldDef) ((RecordDef) typeDef.body()).entries().get(0);
        return ((SimpleRef) field.type().orElseThrow().typeRef()).name();
    }

    private static Instance instanceOf(SchemaDocument document, String declaration) {
        return assertInstanceOf(Instance.class, document.body().declarations().get(declaration).typeDef());
    }

    /**
     * <b>The other half of structural sharing.</b> Sharing keeps a sugar-free declaration findable in the
     * identity-keyed position table by leaving it alone; a declaration that genuinely contains sugar has to be
     * rebuilt, and its position has to be re-registered against the node that replaces it. Without this any
     * record holding a single {@code [T]} field resolves with no position, so every read diagnostic against
     * it -- which is anchored on the enclosing record -- loses its line.
     */
    @Test
    void aRewrittenDeclarationKeepsItsSourcePosition() {
        TsonSchemaParser parser = new TsonSchemaParser("""
                !!meta:"https://tson.io/2026/33/m/meta-kernel.tn"
                {
                  plain => { a: text }

                  sugared => { tags: [text] }
                }""");
        SchemaDocument document = parser.parseSchemaDocument();
        Map<SchemaMap.Declaration, SourcePosition> positions = new IdentityHashMap<>(parser.declarationPositions());

        SchemaDocument desugared = SchemaDesugarer.desugar(document, Set.of(), null, positions);

        Map<String, SchemaMap.Declaration> after = desugared.body().declarations();
        assertNotSame(document.body().declarations().get("sugared"), after.get("sugared"),
                "a declaration containing sugar is genuinely rebuilt");
        assertEquals(5, positions.get(after.get("sugared")).line(), "carried onto the node that replaced it");
        assertSame(document.body().declarations().get("plain"), after.get("plain"));
        assertEquals(3, positions.get(after.get("plain")).line());
    }

    @Test
    void aDocumentWithNoSugarComesBackUntouched() {
        SchemaDocument document = parse("  plain => { a: text  b: integer? }");

        assertSame(document, SchemaDesugarer.desugar(document, Set.of()));
    }

    // ── The desugar table (§5.3) ─────────────────────────────────────────

    @Test
    void aFieldsInlineArrayBecomesAnInstanceDeclarationAndAReference() {
        SchemaDocument document = desugar("  holder => { xs: [text] }");

        SchemaMap.Declaration injected = onlyInjected(document, "array");
        assertEquals(firstFieldType(document, "holder"), injected.name(),
                "the use site refers to the injected declaration by name");
        Instance instance = (Instance) injected.typeDef();
        assertEquals("array", instance.target());
        assertEquals("{ element_type: text }", instanceBody(instance));
    }

    /**
     * The map sugar, the one form the change to type-name-only head resolution added: <code>{K =&gt; V}</code>
     * mirrors the data notation's own {@code {k =&gt; v}} the way {@code [T]} mirrors {@code [a b]}, and it is
     * now the only spelling for a map type -- {@code map<K, V>} resolves its head in the type-name namespace,
     * where the kernel's {@code map} constructor is not reachable.
     */
    @Test
    void aFieldsInlineMapBecomesAnInstanceDeclarationAndAReference() {
        SchemaDocument document = desugar("  holder => { entries: {text => integer} }");

        SchemaMap.Declaration injected = onlyInjected(document, "map");
        assertEquals(firstFieldType(document, "holder"), injected.name());
        assertTrue(injected.name().startsWith("map_text_integer_"), injected.name());
        Instance instance = (Instance) injected.typeDef();
        assertEquals("map", instance.target());
        assertEquals("{ key_type: text  value_type: integer }", instanceBody(instance));
    }

    @Test
    void anInnerFormIsHoistedFirstAndReferredToByTheOuterOne() {
        // The walk is bottom-up, so the inner array is already a plain name when the outer map is built --
        // which is what keeps arbitrarily nested sugar working without a special case.
        SchemaDocument document = desugar("  holder => { m: {text => [integer]} }");

        String innerName = onlyInjected(document, "array").name();
        assertEquals("{ element_type: integer }",
                instanceBody((Instance) onlyInjected(document, "array").typeDef()));
        assertEquals("{ key_type: text  value_type: " + innerName + " }",
                instanceBody((Instance) onlyInjected(document, "map").typeDef()));
    }

    /**
     * Two structurally identical forms share one declaration, wherever in the document they appear. The name
     * is derived from the binding record itself, so this falls out of naming rather than needing a separate
     * dedup table -- and it is what §8.2 asks for: one entry per distinct concrete form, schema-wide.
     */
    @Test
    void twoStructurallyIdenticalFormsBecomeOneDeclaration() {
        SchemaDocument document = desugar("""
                  first => { xs: [text] }
                  second => { ys: [text] }""");

        String injected = onlyInjected(document, "array").name(); // asserts there is exactly one
        assertEquals(injected, firstFieldType(document, "first"));
        assertEquals(injected, firstFieldType(document, "second"));
    }

    /**
     * Identity is the <em>resolved binding record</em>, not the spelling that produced it, so the two ways of
     * writing an exactly-sized array land on the very same entry. That is the rule that also makes a form
     * arising inside a materialised template collapse onto one written directly.
     */
    @Test
    void twoSpellingsOfOneBindingRecordLandOnTheSameEntry() {
        SchemaDocument document = desugar("""
                  sized  => [[text]; 3]
                  ranged => [[text]; 3..3]""");

        assertEquals(1, document.body().declarations().values().stream()
                .filter(d -> d.name().startsWith("array_text_")).count(),
                "one injected inner array, shared by both spellings");
        assertEquals(instanceBody(instanceOf(document, "sized")), instanceBody(instanceOf(document, "ranged")),
                "[T; 3] and [T; 3..3] are one binding record, so one entry");
    }

    /**
     * The derived names are pinned to exact strings, deliberately. They are built from a rendering the
     * desugarer controls rather than from the AST's own {@code toString}, so renaming a component of an
     * {@code ast.schema} record -- or a JDK that formats records differently -- must leave every injected
     * name untouched. Both halves of the name matter: the readable prefix is what a diagnostic shows, and
     * the hash is what an importing schema re-derives to land on an entry an import already materialised.
     * If this test fails, the resolved form of every schema changed.
     */
    @Test
    void derivedNamesDoNotRideOnTheAstsOwnStringForms() {
        assertEquals("array_text_4cc4a482", onlyInjected(desugar("  holder => { xs: [text] }"), "array").name());
        assertEquals("map_text_integer_5c4af9ec",
                onlyInjected(desugar("  holder => { m: {text => integer} }"), "map").name());
    }

    @Test
    void aFormAnImportAlreadyDeclaresIsReferencedNotRedeclared() {
        // The name is derived from the binding record, so an identical form in an imported schema has already
        // produced this type. Redeclaring it would be rejected as a local-vs-import collision -- which is how
        // this surfaced: meta.tn imports the meta-kernel and repeats several of its forms.
        String name = onlyInjected(desugar("  holder => { xs: [text] }"), "array").name();

        SchemaDocument reusing = desugar("  holder => { xs: [text] }", Set.of(name));

        assertEquals(name, firstFieldType(reusing, "holder"), "still refers to it");
        assertTrue(reusing.body().declarations().keySet().stream().noneMatch(n -> n.startsWith("array_")),
                "but declares nothing: " + reusing.body().declarations().keySet());
    }

    // ── Declaration position: the form *is* the construction (§5.6) ──────

    @Test
    void aSizeLessDeclarationLevelArrayIsTheConstructionItself() {
        SchemaDocument document = desugar("  ids => [text]");

        Instance instance = instanceOf(document, "ids");
        assertEquals("array", instance.target());
        assertEquals("{ element_type: text }", instanceBody(instance));
        assertEquals(1, document.body().declarations().size(), "nothing injected alongside it");
    }

    @Test
    void aDeclarationLevelMapIsTheConstructionItself() {
        SchemaDocument document = desugar("  entries => {text => integer}");

        Instance instance = instanceOf(document, "entries");
        assertEquals("map", instance.target());
        assertEquals("{ key_type: text  value_type: integer }", instanceBody(instance));
        assertEquals(1, document.body().declarations().size(), "nothing injected alongside it");
    }

    /**
     * §5.3's size specifier as the {@code min_items}/{@code max_items} pair it binds -- one rule for arrays and
     * maps alike, since both constructors declare the same two fields. There is no template in the middle any
     * more: the kernel's {@code array_min}/{@code array_max}/{@code array_ranged} are deleted, and the four
     * spellings land directly on {@code !array}.
     */
    @Test
    void everySizeSpellingBindsMinItemsAndMaxItemsDirectly() {
        assertEquals("{ element_type: text  min_items: 1  max_items: 5 }",
                instanceBody(instanceOf(desugar("  bounded => [text; 1..5]"), "bounded")));
        assertEquals("{ element_type: text  min_items: 2 }",
                instanceBody(instanceOf(desugar("  atLeast => [text; 2..]"), "atLeast")));
        assertEquals("{ element_type: text  max_items: 9 }",
                instanceBody(instanceOf(desugar("  atMost => [text; ..9]"), "atMost")));
        assertEquals("{ element_type: text  min_items: 3  max_items: 3 }",
                instanceBody(instanceOf(desugar("  exact => [text; 3]"), "exact")));
    }

    /** The map tier admits the same specifier, under the same grammar and the same bindings (§5.3). */
    @Test
    void aMapTakesTheSameSizeSpecifierAsAnArray() {
        assertEquals("{ key_type: text  value_type: integer  min_items: 1  max_items: 5 }",
                instanceBody(instanceOf(desugar("  bounded => {text => integer; 1..5}"), "bounded")));
        assertEquals("{ key_type: text  value_type: integer  min_items: 2 }",
                instanceBody(instanceOf(desugar("  atLeast => {text => integer; 2..}"), "atLeast")));
    }

    /**
     * §5.3's bound-coherence rule on the {@code min_items}/{@code max_items} pair, stated once and applying
     * identically to both tiers: a resolver error where the bounds are literal at schema load.
     */
    @Test
    void anIncoherentSizeRangeIsRejectedForArraysAndMapsAlike() {
        for (String declaration : List.of("  bad => [text; 5..3]", "  bad => {text => integer; 5..3}")) {
            TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                    () -> desugar(declaration), declaration);
            assertTrue(thrown.getMessage().contains("min <= max"), thrown.getMessage());
        }
    }

    /**
     * §5.3 calls {@code [T; 0..]} vacuous and asks for a warning while desugaring it anyway;
     * {@code spec/tson-rev33-changelog.md} #42 rejects the spelling instead, and §5.3's own sentence says why it is
     * worth rejecting rather than tolerating: structural identity (§8.2) makes it a distinct entry meaning
     * exactly what the unconstrained form means.
     */
    @Test
    void aVacuousZeroFloorIsRejectedRatherThanDesugared() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> desugar("  tags => [text; 0..]"));
        assertTrue(thrown.getMessage().contains("'[text; 0..]'"), "quotes the form: " + thrown.getMessage());
    }

    /** Only the open-ended floor is vacuous: {@code 0..M} still pins a ceiling, and desugars as usual. */
    @Test
    void aZeroFloorWithACeilingIsStillARealConstraint() {
        assertEquals("{ element_type: text  min_items: 0  max_items: 5 }",
                instanceBody(instanceOf(desugar("  tags => [text; 0..5]"), "tags")));
    }

    // ── The variadic pair: tuple and choice (§5.3, §5.4) ─────────────────

    @Test
    void aDeclarationLevelTupleBecomesTheConstructionItDenotes() {
        SchemaDocument document = desugar("  pair => [integer, text]");

        Instance instance = instanceOf(document, "pair");
        assertEquals("tuple", instance.target());
        assertEquals("[ { element_type: integer } { element_type: text } ]", tupleElements(instance));
        assertEquals(1, document.body().declarations().size(), "nothing injected alongside it");
    }

    /** At a field position it is hoisted and referred to by name, exactly as inline {@code [T]} is. */
    @Test
    void anInlineTupleIsHoistedIntoItsOwnDeclarationAndReferredToByName() {
        SchemaDocument document = desugar("  holder => { p: [integer, text] }");

        SchemaMap.Declaration injected = onlyInjected(document, "tuple");
        assertEquals(firstFieldType(document, "holder"), injected.name());
        assertTrue(injected.name().startsWith("tuple_integer_text_"), injected.name());
        assertEquals("[ { element_type: integer } { element_type: text } ]",
                tupleElements(assertInstanceOf(Instance.class, injected.typeDef())));
    }

    @Test
    void aDeclarationLevelChoiceBecomesTheConstructionItDenotes() {
        SchemaDocument document = desugar("  contact => (text | integer)");

        Instance instance = instanceOf(document, "contact");
        assertEquals("choice", instance.target());
        assertEquals("{ variants: [text integer] }", variantsOf(instance));
        assertEquals(1, document.body().declarations().size(), "nothing injected alongside it");
    }

    /**
     * A position's own {@code ?} (declaration position only -- the parser rejects one inline) becomes {@code
     * state: OPTIONAL}. A REQUIRED position writes no {@code state} at all: the member is REQUIRED_DEFAULT
     * ({@code state: element_state ~ REQUIRED}), so §5.2's default injection supplies it.
     */
    @Test
    void anOptionalPositionStatesItsStateAndARequiredOneLetsTheDefaultSupplyIt() {
        assertEquals("[ { element_type: integer  state: OPTIONAL } { element_type: text } ]",
                tupleElements(instanceOf(desugar("  pair => [integer?, text]"), "pair")));
    }

    // ── Nested declaration-level forms (§5.3, §12.1) ─────────────────────
    //    Declaration-level container syntax nests inside itself, and the inner form desugars first: the
    //    inner container is injected under its own derived name and the position that held it becomes a
    //    bare reference -- the bottom-up hoist an inline form at a type-ref position already gets.

    @Test
    void aTuplePositionHoldingANestedSizedArrayRefersToTheInjectedInnerArray() {
        SchemaDocument document = desugar("  grid => [[integer; 2], text]");

        SchemaMap.Declaration inner = onlyInjected(document, "array_integer");
        assertEquals("{ element_type: integer  min_items: 2  max_items: 2 }",
                instanceBody(assertInstanceOf(Instance.class, inner.typeDef())));
        assertEquals("[ { element_type: " + inner.name() + " } { element_type: text } ]",
                tupleElements(instanceOf(document, "grid")));
    }

    /** The other nesting direction: a sized array <em>over</em> a nested plain array ({@code [[T]; N]}). */
    @Test
    void aSizedArrayOverANestedArrayRefersToTheInjectedInnerArray() {
        SchemaDocument document = desugar("  rows => [[integer]; 3]");

        SchemaMap.Declaration inner = onlyInjected(document, "array_integer");
        assertEquals("{ element_type: integer }",
                instanceBody(assertInstanceOf(Instance.class, inner.typeDef())));
        assertEquals("{ element_type: " + inner.name() + "  min_items: 3  max_items: 3 }",
                instanceBody(instanceOf(document, "rows")));
    }

    /** A map value nests the same way an array element does -- {@code map-value = container-def / type-ref}. */
    @Test
    void aMapValueHoldingANestedSizedArrayRefersToTheInjectedInnerArray() {
        SchemaDocument document = desugar("  index => {text => [order; 1..]}");

        SchemaMap.Declaration inner = onlyInjected(document, "array_order");
        assertEquals("{ element_type: order  min_items: 1 }",
                instanceBody(assertInstanceOf(Instance.class, inner.typeDef())));
        assertEquals("{ key_type: text  value_type: " + inner.name() + " }",
                instanceBody(instanceOf(document, "index")));
    }

    /** And a map nests inside a map, on both tiers, since {@code container-def} now includes {@code map-def}. */
    @Test
    void aMapValueHoldingANestedMapRefersToTheInjectedInnerMap() {
        SchemaDocument document = desugar("  index => {text => {text => integer; 1..}}");

        SchemaMap.Declaration inner = onlyInjected(document, "map_text_integer");
        assertEquals("{ key_type: text  value_type: integer  min_items: 1 }",
                instanceBody(assertInstanceOf(Instance.class, inner.typeDef())));
        assertEquals("{ key_type: text  value_type: " + inner.name() + " }",
                instanceBody(instanceOf(document, "index")));
    }

    /**
     * Nesting is recursive, and bottom-up needs no special case for depth: {@code [[[T]]]} injects the
     * innermost array first and the middle one refers to it, exactly as the outermost refers to the middle.
     */
    @Test
    void nestingRecursesInnermostFirst() {
        SchemaDocument document = desugar("  deep => [[[integer]]]");

        SchemaMap.Declaration innermost = onlyInjected(document, "array_integer");
        SchemaMap.Declaration middle = onlyInjected(document, "array_array_integer");
        assertEquals("{ element_type: integer }",
                instanceBody(assertInstanceOf(Instance.class, innermost.typeDef())));
        assertEquals("{ element_type: " + innermost.name() + " }",
                instanceBody(assertInstanceOf(Instance.class, middle.typeDef())));
        assertEquals("{ element_type: " + middle.name() + " }",
                instanceBody(instanceOf(document, "deep")));
    }

    /**
     * A nested tuple's <em>position</em> optionality reaches the derived name. Without it {@code [T, U?]} and
     * {@code [T, U]} derive the same name from their element types alone, and the second one written collapses
     * onto the first one injected -- two different types on one entry, silently.
     */
    @Test
    void twoNestedTuplesDifferingOnlyInPositionOptionalityGetSeparateDeclarations() {
        SchemaDocument document = desugar("""
                  strict  => [[integer, text], boolean]
                  relaxed => [[integer, text?], boolean]""");

        List<SchemaMap.Declaration> injected = document.body().declarations().values().stream()
                .filter(declaration -> declaration.name().startsWith("tuple_")).toList();
        assertEquals(2, injected.size(), () -> "expected two injected tuples, got "
                + injected.stream().map(SchemaMap.Declaration::name).toList());
        assertEquals("[ { element_type: integer } { element_type: text } ]",
                tupleElements(assertInstanceOf(Instance.class, injected.get(0).typeDef())));
        assertEquals("[ { element_type: integer } { element_type: text  state: OPTIONAL } ]",
                tupleElements(assertInstanceOf(Instance.class, injected.get(1).typeDef())));
    }

    /** A nested position may carry its own {@code ?} as well, and that is the outer tuple's fact, not the inner's. */
    @Test
    void anOptionalPositionHoldingANestedFormKeepsItsOwnState() {
        SchemaDocument document = desugar("  loose => [[integer, text]?, boolean]");

        SchemaMap.Declaration inner = onlyInjected(document, "tuple");
        assertEquals("[ { element_type: " + inner.name() + "  state: OPTIONAL } { element_type: boolean } ]",
                tupleElements(instanceOf(document, "loose")));
    }

    // ── The element `?` on an array (§5.3) ───────────────────────────────
    //    `state: OPTIONAL` on the resolved array, bound directly alongside the bounds rather than routed
    //    through anything: `[T?; 3]` states both at once and both land on one binding record.

    @Test
    void anOptionalElementStatesItsStateAndARequiredOneLetsTheDefaultSupplyIt() {
        SchemaDocument document = desugar("""
                  slots  => [integer?]
                  strict => [integer]""");

        assertEquals("{ element_type: integer  state: OPTIONAL }", instanceBody(instanceOf(document, "slots")));
        assertEquals("{ element_type: integer }", instanceBody(instanceOf(document, "strict")));
    }

    @Test
    void anOptionalElementAndASizeLandOnOneBindingRecord() {
        assertEquals("{ element_type: integer  state: OPTIONAL  min_items: 3  max_items: 3 }",
                instanceBody(instanceOf(desugar("  triple => [integer?; 3]"), "triple")));
    }

    /**
     * The state reaches the derived name: without it {@code [T?]} and {@code [T]} derive the same name and the
     * second one written collapses onto the first one injected.
     */
    @Test
    void twoNestedArraysDifferingOnlyInElementStateGetSeparateDeclarations() {
        SchemaDocument document = desugar("""
                  loose  => [[integer?; 3], text]
                  strict => [[integer; 3], text]""");

        List<SchemaMap.Declaration> injected = document.body().declarations().values().stream()
                .filter(declaration -> declaration.name().startsWith("array_integer_")).toList();
        assertEquals(2, injected.size(), () -> "expected two injected arrays, got "
                + injected.stream().map(SchemaMap.Declaration::name).toList());
        assertEquals("{ element_type: integer  state: OPTIONAL  min_items: 3  max_items: 3 }",
                instanceBody(assertInstanceOf(Instance.class, injected.get(0).typeDef())));
        assertEquals("{ element_type: integer  min_items: 3  max_items: 3 }",
                instanceBody(assertInstanceOf(Instance.class, injected.get(1).typeDef())));
    }

    // ── Generic application heads (§3.3.1, §5.10) ────────────────────────

    /**
     * A sugar form naming one of the enclosing declaration's own parameters lifts to an <b>open</b> synthetic
     * -- a template of its own, over just the parameters it uses -- and the field applies it straight back.
     * The parameters are renamed positionally, so two templates alike up to renaming land on one entry (§8.2).
     */
    @Test
    void aParameterBearingFormLiftsToAnOpenSynthetic() {
        SchemaDocument document = desugar("  box => <T> { v: [T] }");

        SchemaMap.Declaration lifted = document.body().declarations().values().stream()
                .filter(declaration -> declaration.name().startsWith("array_p0_")).findFirst().orElseThrow();
        InstanceTemplate template = assertInstanceOf(InstanceTemplate.class, lifted.typeDef());
        assertEquals(List.of("p0"), template.typeParams());
        assertEquals("array", template.target());
        assertEquals(new TypeArg.Ref(new SimpleRef("p0")), template.bindings().get(0).value());

        RecordDef box = (RecordDef) ((StructuralTypeDef) document.body().declarations().get("box").typeDef())
                .body();
        assertEquals(new GenericRef(lifted.name(), List.of(new TypeArg.Ref(new SimpleRef("T")))),
                ((FieldDef) box.entries().get(0)).type().orElseThrow().typeRef());
    }

    /** Two templates differing only in what they call their parameter are one template, so one entry (§8.2). */
    @Test
    void twoOpenFormsAlikeUpToRenamingLandOnOneEntry() {
        SchemaDocument document = desugar("""
                  box   => <T> { v: [T] }
                  crate => <A> { w: [A] }""");

        assertEquals(1, document.body().declarations().keySet().stream()
                .filter(name -> name.startsWith("array_p0_")).count(),
                () -> "one open synthetic, shared: " + document.body().declarations().keySet());
    }

    /** A template's own body is the open construction, not a reference to a lifted one -- D5, one tier up. */
    @Test
    void aTemplatesOwnSugarBodyIsTheInstanceTemplate() {
        SchemaDocument document = desugar("  vector => <T> [T]");

        InstanceTemplate template = assertInstanceOf(InstanceTemplate.class,
                document.body().declarations().get("vector").typeDef());
        assertEquals(List.of("T"), template.typeParams(), "the declaration's own parameters, as written");
        assertEquals("array", template.target());
    }

    /** A concrete form inside a template still lifts closed: D5 has one rule, and the parameters do not enter it. */
    @Test
    void aConcreteFormInsideATemplateStillLiftsClosed() {
        SchemaDocument document = desugar("  box => <T> { v: T  w: [text] }");

        SchemaMap.Declaration lifted = document.body().declarations().values().stream()
                .filter(declaration -> declaration.name().startsWith("array_text_")).findFirst().orElseThrow();
        assertInstanceOf(Instance.class, lifted.typeDef());
    }

    /**
     * A <b>record</b> template's application passes through untouched. Substitution happens over the
     * <em>resolved</em> open form (`TemplateMaterialiser`), not over the AST, so this phase leaves the head
     * and its arguments alone and the application reaches resolution intact.
     */
    @Test
    void applyingARecordTemplateIsLeftForMaterialisation() {
        SchemaDocument document = parse("""
                  box => <T> { v: T }
                  holder => { b: box<text> }""");

        assertSame(document, SchemaDesugarer.desugar(document, Set.of()));
    }

    /**
     * A template whose body writes a container sugar form passes through as well: the form lifts open, and the
     * application closes it at materialisation. What this phase used to refuse is now the mechanism.
     */
    @Test
    void applyingATemplateWhoseBodyCarriesSugarPassesThroughToo() {
        SchemaDocument document = desugar("""
                  box => <T> { v: [T] }
                  holder => { b: box<text> }""");

        RecordDef holder = (RecordDef) ((StructuralTypeDef) document.body().declarations().get("holder")
                .typeDef()).body();
        assertEquals(new GenericRef("box", List.of(new TypeArg.Ref(new SimpleRef("text")))),
                ((FieldDef) holder.entries().get(0)).type().orElseThrow().typeRef(),
                "left for materialisation, arguments intact");
    }

    /**
     * An <em>imported</em> head passes through too, and needs no check here even though this phase is handed
     * only the imported names. A template carrying a sugar form cannot link, so it cannot have been
     * registered, so it cannot be imported: every imported template is sugar-free by construction.
     */
    @Test
    void applyingAnImportedTemplateIsLeftForMaterialisationToo() {
        SchemaDocument document = parse("  holder => { b: elsewhere<text> }");

        assertSame(document, SchemaDesugarer.desugar(document, Set.of("elsewhere")));
    }

    /**
     * The kernel's own container constructors are no longer reachable at a head. {@code map<text, integer>}
     * resolves {@code map} in the type-name namespace, finds nothing, and stays an ordinary unresolved
     * reference for the linker to report over the whole schema -- there is nothing here to apply.
     */
    @Test
    void aKernelConstructorNameAtAHeadIsNoLongerAnApplication() {
        SchemaDocument document = parse("  holder => { m: map<text, integer> }");

        assertSame(document, SchemaDesugarer.desugar(document, Set.of()));
    }

    /** Applying arguments to a local declaration that takes none is the author's error, not a library gap. */
    @Test
    void applyingArgumentsToSomethingThatTakesNoneIsAnAuthorError() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> desugar("""
                          plain => { a: text }
                          holder => { b: plain<text> }"""));
        assertTrue(thrown.getMessage().contains("declares no type parameters"), thrown.getMessage());
    }

    @Test
    void anUnknownHeadIsStillPassedThrough() {
        SchemaDocument document = parse("  holder => { b: nowhere<text> }");

        assertSame(document, SchemaDesugarer.desugar(document, Set.of()));
    }

    // ── Rendering helpers ────────────────────────────────────────────────

    /**
     * Renders a variadic {@code Instance}'s one collection-valued field as {@code [ { member: value  ... }
     * ... ]} -- {@link #instanceBody}'s counterpart for a body whose field holds records rather than tokens.
     */
    private static String tupleElements(Instance instance) {
        var record = (io.ltr8.tson.compiler.ast.RecordValue) instance.value().coreValue();
        var elements = (io.ltr8.tson.compiler.ast.ArrayValue) record.fields().get(0).value().value().coreValue();
        StringBuilder out = new StringBuilder("[");
        for (var element : elements.elements()) {
            var members = (io.ltr8.tson.compiler.ast.RecordValue) element.value().coreValue();
            StringBuilder rendered = new StringBuilder("{");
            for (var member : members.fields()) {
                var token = (io.ltr8.tson.compiler.ast.TokenValue) member.value().value().coreValue();
                rendered.append(' ').append(member.name()).append(": ").append(token.text()).append(' ');
            }
            out.append(' ').append(rendered.append('}').toString().replace("  }", " }"));
        }
        return out.append(" ]").toString();
    }

    /** Renders {@code !choice { variants: [...] }} as {@code { variants: [a b] }}. */
    private static String variantsOf(Instance instance) {
        var record = (io.ltr8.tson.compiler.ast.RecordValue) instance.value().coreValue();
        var elements = (io.ltr8.tson.compiler.ast.ArrayValue) record.fields().get(0).value().value().coreValue();
        return "{ variants: [" + elements.elements().stream()
                .map(e -> ((io.ltr8.tson.compiler.ast.TokenValue) e.value().coreValue()).text())
                .reduce((a, b) -> a + " " + b).orElse("") + "] }";
    }

    /** Renders an {@code Instance}'s binding record as {@code { field: value  ... }} for readable assertions. */
    private static String instanceBody(Instance instance) {
        var record = (io.ltr8.tson.compiler.ast.RecordValue) instance.value().coreValue();
        StringBuilder out = new StringBuilder("{");
        for (var field : record.fields()) {
            var token = (io.ltr8.tson.compiler.ast.TokenValue) field.value().value().coreValue();
            out.append(' ').append(field.name()).append(": ").append(token.text()).append(' ');
        }
        return out.append('}').toString().replace("  }", " }");
    }
}
