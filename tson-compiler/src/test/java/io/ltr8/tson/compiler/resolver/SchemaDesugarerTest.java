package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.SchemaPositions;
import io.ltr8.tson.compiler.TsonSchemaParser;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.compiler.ast.ReferenceValue;
import io.ltr8.tson.compiler.ast.schema.ApplicationRef;
import io.ltr8.tson.compiler.ast.schema.FieldDef;
import io.ltr8.tson.compiler.ast.schema.Instance;
import io.ltr8.tson.compiler.ast.schema.NameRef;
import io.ltr8.tson.compiler.ast.schema.RecordDef;
import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.compiler.ast.schema.SchemaMap;
import io.ltr8.tson.compiler.ast.schema.SiblingRef;
import io.ltr8.tson.compiler.ast.schema.StructuralTypeDef;
import io.ltr8.tson.base.SchemaValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * <p>No fixture's desugaring consults an import, and that is the point of the phase's current shape: the sugar
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
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !schema {
                %s
                }
                """.formatted(declarations)).parseSchemaDocument();
    }

    private static SchemaDocument desugar(String declarations) {
        return SchemaDesugarer.desugar(parse(declarations));
    }

    /** The construction written in place at {@code declaration}'s first field, rendered ({@link #rendered}). */
    private static String firstFieldConstruction(SchemaDocument document, String declaration) {
        StructuralTypeDef typeDef = (StructuralTypeDef) document.body().declarations().get(declaration).typeDef();
        FieldDef field = (FieldDef) ((RecordDef) typeDef.body()).entries().get(0);
        return rendered(assertInstanceOf(io.ltr8.tson.compiler.ast.schema.InlineConstruction.class,
                field.type().orElseThrow().typeRef()).value());
    }

    /**
     * A binding record as text, the way a resolved form writes it: a tag where the value carries one, a token as its
     * text, a record as {@code { field: value }}, a list as {@code [a b]}.
     */
    private static String rendered(DataValue value) {
        String tag = value.typeRef().map(t -> "!" + t + " ").orElse("");
        return tag + switch (value.coreValue()) {
            case io.ltr8.tson.compiler.ast.TokenValue token -> token.text();
            case io.ltr8.tson.compiler.ast.ReferenceValue reference -> reference.toString();
            case io.ltr8.tson.compiler.ast.RecordValue record -> record.fields().stream()
                    .map(f -> f.name() + ": " + rendered(f.value()))
                    .collect(java.util.stream.Collectors.joining("  ", "{ ", " }"));
            case io.ltr8.tson.compiler.ast.ArrayValue array -> array.elements().stream()
                    .map(SchemaDesugarerTest::rendered).collect(java.util.stream.Collectors.joining(" ", "[", "]"));
            default -> String.valueOf(value.coreValue());
        };
    }

    // ── A sugar form at a field's type is the construction it denotes, in place (§5.3) ─────────────

    /** No declaration is added for a form at a field: the construction stands where it is written. */
    @Test
    void aFieldsArrayIsTheConstructionInPlace() {
        SchemaDocument document = desugar("  holder => { xs: [text] }");

        assertEquals(Set.of("holder"), document.body().declarations().keySet());
        assertEquals("!meta.array { element_type: text }", firstFieldConstruction(document, "holder"));
    }

    @Test
    void aFieldsMapIsTheConstructionInPlace() {
        assertEquals("!meta.map { key_type: text  value_type: integer }",
                firstFieldConstruction(desugar("  holder => { entries: {text => integer} }"), "holder"));
    }

    @Test
    void aFieldsTupleIsTheConstructionInPlace() {
        assertEquals("!meta.tuple { elements: [{ element_type: integer } { element_type: text  voidable: true }] }",
                firstFieldConstruction(desugar("  holder => { pair: [integer, text?] }"), "holder"));
    }

    /** A nested form is its construction in the wrapper a type written in place takes, at any depth. */
    @Test
    void aNestedFormIsItsConstructionInTheWrapper() {
        assertEquals("!meta.array { element_type: { of: !meta.array { element_type: { of: !meta.array { "
                        + "element_type: integer } } } } }",
                instanceBody(desugar("  deep => [[[integer]]]"), "deep"));
        assertEquals("!meta.map { key_type: text  value_type: { of: !meta.array { element_type: order  "
                        + "min_items: 1 } } }",
                instanceBody(desugar("  index => {text => [order; 1..]}"), "index"));
        assertEquals("!meta.tuple { elements: [{ element_type: { of: !meta.tuple { elements: [{ element_type: "
                        + "integer } { element_type: text }] } }  voidable: true } { element_type: boolean }] }",
                instanceBody(desugar("  loose => [[integer, text]?, boolean]"), "loose"));
    }

    /** A map's value takes the element's {@code ?}; an unmarked one states nothing, a different construction. */
    @Test
    void aVoidableMapValueBindsTheVoidableField() {
        assertEquals("!meta.map { key_type: text  value_type: integer  voidable: true }",
                firstFieldConstruction(desugar("  holder => { m: {text => integer?} }"), "holder"));
        assertEquals("!meta.map { key_type: text  value_type: integer }",
                firstFieldConstruction(desugar("  holder => { m: {text => integer} }"), "holder"));
    }

    /** A concrete form inside an open record is written in place exactly as outside one. */
    @Test
    void aConcreteFormInsideATemplateIsInPlace() {
        assertEquals("!meta.array { element_type: text }",
                firstFieldConstruction(desugar("  tmpl => <T> { tags: [text]  v: T }"), "tmpl"));
    }

    /** A form as an argument is refused: an argument is a name or a parameter (§5.10), the form declared first. */
    @Test
    void aFormAsAnArgumentIsRefused() {
        SchemaValidationException thrown = assertThrows(SchemaValidationException.class, () -> desugar("""
                  page => <T> { items: T }
                  orders => { p: page<[text]> }"""));
        assertTrue(thrown.getMessage().contains("'[text]' is a construction passed as an argument"),
                thrown.getMessage());
    }

    /** The body of a declaration whose body is sugar, the construction it is, rendered. */
    private static String instanceBody(SchemaDocument document, String declaration) {
        return rendered(instanceOf(document, declaration).value());
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
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !schema {
                  plain => { a: text }

                  sugared => { tags: [text] }
                }""");
        SchemaDocument document = parser.parseSchemaDocument();
        SchemaPositions positions = parser.schemaPositions();

        SchemaDocument desugared = SchemaDesugarer.desugar(document, null, positions);

        Map<String, SchemaMap.Declaration> after = desugared.body().declarations();
        assertNotSame(document.body().declarations().get("sugared"), after.get("sugared"),
                "a declaration containing sugar is genuinely rebuilt");
        assertEquals(5, positions.of(after.get("sugared")).orElseThrow().line(),
                "carried onto the node that replaced it");
        assertSame(document.body().declarations().get("plain"), after.get("plain"));
        assertEquals(3, positions.of(after.get("plain")).orElseThrow().line());
    }

    @Test
    void aDocumentWithNoSugarComesBackUntouched() {
        SchemaDocument document = parse("  plain => { a: text  b: integer? }");

        assertSame(document, SchemaDesugarer.desugar(document));
    }

    // ── The desugar table (§5.3) ─────────────────────────────────────────

    // ── Declaration position: the form *is* the construction (§5.6) ──────

    @Test
    void aSizeLessDeclarationLevelArrayIsTheConstructionItself() {
        SchemaDocument document = desugar("  ids => [text]");

        Instance instance = instanceOf(document, "ids");
        assertEquals("meta.array", instance.target());
        assertEquals("{ element_type: text }", instanceBody(instance));
        assertEquals(1, document.body().declarations().size(), "nothing injected alongside it");
    }

    @Test
    void aDeclarationLevelMapIsTheConstructionItself() {
        SchemaDocument document = desugar("  entries => {text => integer}");

        Instance instance = instanceOf(document, "entries");
        assertEquals("meta.map", instance.target());
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
     * <b>An incoherent size range desugars, and is refused later.</b> §5.3's {@code min <= max} rule belongs
     * to the container family rather than to this phase: the sugar and the {@code !array { ... min_items:
     * ... }} body it denotes are the same type, and a rule stated here would refuse one spelling and admit
     * the other. So this phase's job ends at producing the binding, and {@code ContainerBoundCoherenceTest}
     * owns the verdict for every spelling that reaches it.
     */
    @Test
    void anIncoherentSizeRangeStillDesugarsBecauseTheRuleIsTheFamilysOwn() {
        assertEquals("{ element_type: text  min_items: 5  max_items: 3 }",
                instanceBody(instanceOf(desugar("  bad => [text; 5..3]"), "bad")));
    }

    /**
     * §5.3 makes {@code [T; 0..]} a resolver error, and says why it is worth rejecting rather than
     * tolerating: structural identity (§8.2) makes it a distinct entry meaning exactly what the
     * unconstrained form means, and the diagnostic SHOULD say so.
     */
    @Test
    void aVacuousZeroFloorIsRejectedRatherThanDesugared() {
        SchemaValidationException thrown = assertThrows(SchemaValidationException.class,
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
        assertEquals("meta.tuple", instance.target());
        assertEquals("[ { element_type: integer } { element_type: text } ]", tupleElements(instance));
        assertEquals(1, document.body().declarations().size(), "nothing injected alongside it");
    }

    @Test
    void aDeclarationLevelChoiceBecomesTheConstructionItDenotes() {
        SchemaDocument document = desugar("  contact => (text | integer)");

        Instance instance = instanceOf(document, "contact");
        assertEquals("meta.choice", instance.target());
        assertEquals("{ variants: [text integer] }", variantsOf(instance));
        assertEquals(1, document.body().declarations().size(), "nothing injected alongside it");
    }

    /**
     * A position's own {@code ?} (declaration position only -- the parser rejects one inline) becomes {@code
     * voidable: true}. An unmarked position writes no {@code voidable} at all: the member is a default
     * ({@code voidable?: boolean ~ false}), so §5.2's default injection supplies it.
     */
    @Test
    void aVoidablePositionStatesItAndAnUnmarkedOneLetsTheDefaultSupplyIt() {
        assertEquals("[ { element_type: integer  voidable: true } { element_type: text } ]",
                tupleElements(instanceOf(desugar("  pair => [integer?, text]"), "pair")));
    }

    // ── Nested declaration-level forms (§5.3, §12.1) ─────────────────────
    //    Declaration-level container syntax nests inside itself, and the inner form desugars first: the
    //    inner container is injected under its own derived name and the position that held it becomes a
    //    bare reference -- the bottom-up hoist an inline form at a type-ref position already gets.

    // ── The element `?` on an array (§5.3) ───────────────────────────────
    //    `voidable: true` on the resolved array, bound directly alongside the bounds rather than routed
    //    through anything: `[T?; 3]` states both at once and both land on one binding record.

    @Test
    void aVoidableElementStatesItAndAnUnmarkedOneLetsTheDefaultSupplyIt() {
        SchemaDocument document = desugar("""
                  slots  => [integer?]
                  strict => [integer]""");

        assertEquals("{ element_type: integer  voidable: true }", instanceBody(instanceOf(document, "slots")));
        assertEquals("{ element_type: integer }", instanceBody(instanceOf(document, "strict")));
    }

    @Test
    void aVoidableElementAndASizeLandOnOneBindingRecord() {
        assertEquals("{ element_type: integer  voidable: true  min_items: 3  max_items: 3 }",
                instanceBody(instanceOf(desugar("  triple => [integer?; 3]"), "triple")));
    }

    // ── Generic application heads (§3.3.1, §5.10) ────────────────────────

    /**
     * Inside an open record a container over one of its parameters is the reference it spells, {@code [T]} the
     * application {@code meta.array<T>} and {@code {K => V}} the application {@code meta.map<K V>}, held as written:
     * nothing is lifted for it (§5.10).
     */
    @Test
    void aContainerOverAParameterIsTheApplicationItSpells() {
        SchemaDocument document = desugar("  box => <T> { v: [T]  m: {text => T}  n: [[T]] }");

        assertEquals(Set.of("box"), document.body().declarations().keySet(), "nothing lifted");
        RecordDef box = (RecordDef) ((StructuralTypeDef) document.body().declarations().get("box").typeDef()).body();
        assertEquals(new ApplicationRef("meta.array", List.of(new NameRef("T"))),
                ((FieldDef) box.entries().get(0)).type().orElseThrow().typeRef());
        assertEquals(new ApplicationRef("meta.map", List.of(new NameRef("text"), new NameRef("T"))),
                ((FieldDef) box.entries().get(1)).type().orElseThrow().typeRef());
        assertEquals(new ApplicationRef("meta.array", List.of(new ApplicationRef("meta.array",
                        List.of(new NameRef("T"))))),
                ((FieldDef) box.entries().get(2)).type().orElseThrow().typeRef());
    }

    /** A form over a parameter that is no reference is refused, naming the form (§5.10). */
    @Test
    void aFormOverAParameterThatIsNoReferenceIsRefused() {
        for (String form : List.of("[T; 1..]", "[T?]", "{text => T; ..10}", "( T | text )", "[T, text]")) {
            SchemaValidationException thrown = assertThrows(SchemaValidationException.class,
                    () -> desugar("  box => <T> { v: " + form + " }"), form);
            assertTrue(thrown.getMessage().contains("stands over the parameter 'T' and is no reference"),
                    thrown.getMessage());
        }
    }

    /** A template's body is a record body: a parameter list before anything else is refused (§5.10). */
    @Test
    void aTemplateBodyThatIsNoRecordIsRefused() {
        for (String body : List.of("<T> [T]", "<T> [T; 3]", "<V> {text => V}", "<T> ( T | text )", "<A, B> [A, B]",
                "<T> T", "<B> pair<uuid, B>")) {
            SchemaValidationException thrown = assertThrows(SchemaValidationException.class,
                    () -> desugar("  bad => " + body), body);
            assertTrue(thrown.getMessage().contains("'bad': a template's body is a record body"),
                    thrown.getMessage());
        }
    }

    /**
     * An open record passes through as the record body it is written as, and the <b>application</b> of it too:
     * substitution happens over the resolved open record ({@code TemplateMaterialiser}), not over the AST, so this
     * phase leaves {@code box<text>}'s head and arguments alone.
     */
    @Test
    void anOpenRecordAndItsApplicationPassThrough() {
        SchemaDocument document = parse("""
                  box => <T> { v: T }
                  holder => { b: box<text> }""");

        assertSame(document, SchemaDesugarer.desugar(document));
    }

    /**
     * A template whose body writes a container over a parameter passes through as well: the form is the reference
     * it spells, and the application closes it at materialisation.
     */
    @Test
    void applyingATemplateWhoseBodyCarriesSugarPassesThroughToo() {
        SchemaDocument document = desugar("""
                  box => <T> { v: [T] }
                  holder => { b: box<text> }""");

        RecordDef holder = (RecordDef) ((StructuralTypeDef) document.body().declarations().get("holder")
                .typeDef()).body();
        assertEquals(new ApplicationRef("box", List.of(new NameRef("text"))),
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

        assertSame(document, SchemaDesugarer.desugar(document));
    }

    /**
     * The kernel's own container constructors are no longer reachable at a head. {@code map<text, integer>}
     * resolves {@code map} in the type-name namespace, finds nothing, and stays an ordinary unresolved
     * reference for the linker to report over the whole schema -- there is nothing here to apply.
     */
    @Test
    void aKernelConstructorNameAtAHeadIsNoLongerAnApplication() {
        SchemaDocument document = parse("  holder => { m: map<text, integer> }");

        assertSame(document, SchemaDesugarer.desugar(document));
    }

    /** Applying arguments to a local declaration that takes none is the author's error, not a library gap. */
    @Test
    void applyingArgumentsToSomethingThatTakesNoneIsAnAuthorError() {
        SchemaValidationException thrown = assertThrows(SchemaValidationException.class,
                () -> desugar("""
                          plain => { a: text }
                          holder => { b: plain<text> }"""));
        assertTrue(thrown.getMessage().contains("declares no type parameters"), thrown.getMessage());
    }

    @Test
    void anUnknownHeadIsStillPassedThrough() {
        SchemaDocument document = parse("  holder => { b: nowhere<text> }");

        assertSame(document, SchemaDesugarer.desugar(document));
    }

    // ── Rendering helpers ────────────────────────────────────────────────

    /**
     * Renders a variadic {@code Instance}'s one collection-valued field as {@code [ { member: value  ... }
     * ... ]} -- {@link #instanceBody}'s counterpart for a body whose field holds records rather than tokens.
     */
    private static String tupleElements(Instance instance) {
        var record = (io.ltr8.tson.compiler.ast.RecordValue) instance.value().coreValue();
        var elements = (io.ltr8.tson.compiler.ast.ArrayValue) record.fields().get(0).value().coreValue();
        StringBuilder out = new StringBuilder("[");
        for (var element : elements.elements()) {
            var members = (io.ltr8.tson.compiler.ast.RecordValue) element.coreValue();
            StringBuilder rendered = new StringBuilder("{");
            for (var member : members.fields()) {
                var token = (io.ltr8.tson.compiler.ast.TokenValue) member.value().coreValue();
                rendered.append(' ').append(member.name()).append(": ").append(token.text()).append(' ');
            }
            out.append(' ').append(rendered.append('}').toString().replace("  }", " }"));
        }
        return out.append(" ]").toString();
    }

    /** Renders {@code !choice { variants: [...] }} as {@code { variants: [a b] }}. */
    private static String variantsOf(Instance instance) {
        var record = (io.ltr8.tson.compiler.ast.RecordValue) instance.value().coreValue();
        var elements = (io.ltr8.tson.compiler.ast.ArrayValue) record.fields().get(0).value().coreValue();
        return "{ variants: [" + elements.elements().stream()
                .map(e -> ((io.ltr8.tson.compiler.ast.TokenValue) e.coreValue()).text())
                .reduce((a, b) -> a + " " + b).orElse("") + "] }";
    }

    // ── §5.2's position rule: a sibling reference stands only at a record field's type ─────────────

    /** At a record field's type, and a group member's, a sibling reference passes through as written. */
    @Test
    void aSiblingReferenceAtAFieldsTypeIsKeptAsWritten() {
        SchemaDocument document = desugar("  cell => { kind: meta.type_ref  v?: kind!  ( a: kind! | b: text ) }");
        StructuralTypeDef typeDef = (StructuralTypeDef) document.body().declarations().get("cell").typeDef();
        FieldDef field = (FieldDef) ((RecordDef) typeDef.body()).entries().get(1);
        assertEquals(new SiblingRef("kind"), field.type().orElseThrow().typeRef());
    }

    /** An open record's field keeps it in the record body as written, as a closed record's does. */
    @Test
    void anOpenRecordFieldKeepsASiblingReference() {
        StructuralTypeDef typeDef = assertInstanceOf(StructuralTypeDef.class, desugar(
                "  cell => <T> { kind: meta.type_ref  v?: kind!  w: T }").body().declarations().get("cell").typeDef());
        FieldDef field = (FieldDef) ((RecordDef) typeDef.body()).entries().get(1);
        assertEquals(new SiblingRef("kind"), field.type().orElseThrow().typeRef());
    }

    /** Anywhere else in sugar it is refused before any lift, naming the slot. */
    @Test
    void aSiblingReferenceOffAFieldsTypeIsRefusedNamingTheSlot() {
        Map<String, String> slots = Map.of(
                "  cell => { kind: meta.type_ref  v: [ kind! ] }", "an array's element type",
                "  cell => { kind: meta.type_ref  v: { text => kind! } }", "a map's value type",
                "  cell => { kind: meta.type_ref  v: { kind! => text } }", "a map's key type",
                "  cell => { kind: meta.type_ref  v: ( kind! | text ) }", "a choice variant",
                "  cell => { kind: meta.type_ref  v: [ kind! text ] }", "a tuple element",
                "  box => <T> { v: T }\n  cell => { kind: meta.type_ref  v: box< kind! > }", "a template argument",
                "  x => kind!", "an alias target",
                "  x => kind! ^ {}", "a refinement source",
                "  x => kind! & { note: text }", "a composition operand");
        slots.forEach((declarations, slot) -> {
            SchemaValidationException thrown = assertThrows(SchemaValidationException.class,
                    () -> desugar(declarations), declarations);
            assertEquals("a sibling reference ('kind!') stands only at a record field's type ([TSON-SCHEMA] §5.2), "
                    + "not at " + slot, thrown.getMessage(), declarations);
        });
    }

    /** An application in a slot: the data grammar's reference, {@code head<arg>}. */
    private static CoreValue applicationValue(String head, String argument) {
        return new ReferenceValue(io.ltr8.tson.schema.meta.TypeRef.of(head,
                List.of(io.ltr8.tson.schema.meta.TypeRef.of(argument))));
    }

    private static DataValue plain(CoreValue value) {
        return new DataValue(List.of(), Optional.empty(), value);
    }

    /** Renders an {@code Instance}'s binding record as {@code { field: value  ... }} for readable assertions. */
    private static String instanceBody(Instance instance) {
        var record = (io.ltr8.tson.compiler.ast.RecordValue) instance.value().coreValue();
        StringBuilder out = new StringBuilder("{");
        for (var field : record.fields()) {
            var token = (io.ltr8.tson.compiler.ast.TokenValue) field.value().coreValue();
            out.append(' ').append(field.name()).append(": ").append(token.text()).append(' ');
        }
        return out.append('}').toString().replace("  }", " }");
    }
}
