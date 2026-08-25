package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonCanonicalIdentity;
import io.ltr8.tson.schema.TsonSchemaValidationException;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.InstanceTemplate;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.TemplateArgument;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeRef;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A container position holding a <b>template application</b> rather than a name -- {@code [tree<T>; 1..]},
 * {@code [[T]]}, {@code [[T; N]; N]}. These are the change report's own §8 fixtures, in the spelling §8
 * writes them.
 *
 * <p><b>The position has no name at desugar time, which is the whole difficulty.</b> A closed form
 * ({@code [box<text>]}) names the entry {@code box<text>} denotes, and that entry does not exist until
 * materialisation runs a phase later -- so it stays refused, and {@link #aClosedContainerCannotHoldAn
 * Application} pins the reason. An <b>open</b> form needs no name: its binding holds a {@code type_ref}
 * directly, arguments intact, and materialisation closes it once the parameters are bound.
 */
class ApplicationInContainerPositionTest {

    private static final String ID = "https://example.test/nested.tn";

    private static TsonCompiledSchema compile(String declarations) {
        String schema = """
                !!id:"https://example.test/nested.tn"
                !!meta:"https://tson.io/2026/33/m/meta.tn"
                !!import:"https://tson.io/2026/33/m/core.tn"
                {
                %s
                }
                """.formatted(declarations);
        TsonSchemaSource source = uri -> {
            if (TsonCanonicalIdentity.sameIdentity(uri, ID)) {
                return schema;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        TsonCompiledMetaRegistry core =
                TsonCompiledMetaRegistry.withStandardLibrary(SchemaMetaNameBinder.defaultContext(), source);
        return TsonCompiledSchemaRegistry.tree(core).get(ID);
    }

    private static String fieldType(TsonCompiledSchema compiled, String record, String field) {
        RecordBody body = (RecordBody) compiled.schema().entries().get(record).body();
        return body.fields().stream().filter(f -> f.name().equals(field)).findFirst().orElseThrow()
                .type().name();
    }

    /**
     * The entry a field's type resolves to, following the instantiation entry's hop. A closure whose body is a
     * synthetic is named by <em>two</em> entries -- the form, and the instantiation that records the
     * application -- so a fixture asking about the shape has to step past the second to reach the first.
     */
    private static TypeDefinition formBehind(TsonCompiledSchema compiled, String record, String field) {
        TypeDefinition entry = compiled.schema().entries().get(fieldType(compiled, record, field));
        return entry.body() instanceof Reference reference
                ? compiled.schema().entries().get(reference.target()) : entry;
    }

    /** The entries this schema derived rather than the author declaring them -- synthetics and instantiations. */
    private static List<String> derived(TsonCompiledSchema compiled) {
        return compiled.schema().entries().entrySet().stream()
                .filter(e -> e.getValue().position().isEmpty()).map(java.util.Map.Entry::getKey).sorted().toList();
    }

    /** Derived entries still carrying parameters -- the open half of §8's counts. */
    private static List<String> openNames(TsonCompiledSchema compiled) {
        return derived(compiled).stream()
                .filter(n -> compiled.schema().entries().get(n).body() instanceof InstanceTemplate).toList();
    }

    private static long openSynthetics(TsonCompiledSchema compiled) {
        return openNames(compiled).size();
    }

    /** Derived entries that are usable as types -- closed synthetics and instantiation entries alike. */
    private static long closedDerived(TsonCompiledSchema compiled) {
        return derived(compiled).size() - openSynthetics(compiled);
    }

    // ── §8's tree fixture: the knot tied through a synthetic ──────────────

    /**
     * {@code tree => <T> { value: T  children: [tree<T>; 1..] }} closed via {@code tree<text>}. The array the
     * sugar lifts to is <b>open</b>, and its own binding names {@code tree} -- the very template whose field
     * holds it. Closing {@code tree<text>} therefore reaches back into an entry still under construction, and
     * the memo is what answers: the synthetic's {@code element_type} names the instantiation entry, recorded
     * before that entry completes.
     */
    @Test
    void theTreeFixtureTiesTheKnotThroughTheSynthetic() {
        TsonCompiledSchema compiled = compile("""
                  tree => <T> { value: T  children: [tree<T>; 1..]? }
                  use  => { t: tree<text> }""");

        String instantiation = fieldType(compiled, "use", "t");
        String synthetic = fieldType(compiled, instantiation, "children");
        ArrayBody children = assertInstanceOf(ArrayBody.class,
                compiled.schema().entries().get(synthetic).body());

        assertEquals(TypeRef.of(instantiation), children.elementType(), "the knot");
        assertEquals(Optional.of(BigInteger.ONE), children.minItems());
        assertEquals(TypeRef.of("text"), ((RecordBody) compiled.schema().entries().get(instantiation).body())
                .fields().get(0).type(), "T bound at the value field too");
    }

    /** The open form the fixture rests on: an array template whose element is an application of {@code tree}. */
    @Test
    void theLiftedArrayIsOpenAndItsBindingKeepsTheApplication() {
        TsonCompiledSchema compiled = compile("""
                  tree => <T> { value: T  children: [tree<T>; 1..]? }
                  use  => { t: tree<text> }""");

        TypeDefinition open = compiled.schema().entries().values().stream()
                .filter(d -> d.body() instanceof InstanceTemplate).findFirst().orElseThrow();
        InstanceTemplate body = (InstanceTemplate) open.body();

        assertEquals(List.of("p0"), open.parameters(), "renamed positionally, as every open synthetic is");
        TemplateArgument.Ref element = assertInstanceOf(TemplateArgument.Ref.class,
                body.bindings().get("element_type"));
        assertEquals("tree", element.typeRef().name());
        assertEquals(1, element.typeRef().arguments().size(), "still an application, awaiting p0");
    }

    /**
     * The same shape unsized, which is the one that can actually be read: a recursive type terminates only if
     * its recursive position can be empty.
     */
    @Test
    void anUnsizedTreeReadsRealData() {
        TsonCompiledSchema compiled = compile("""
                  tree => <T> { value: T  children: [tree<T>] }
                  use  => { t: tree<text> }""");

        assertNotNull(compiled.get("use").read(TestDocuments.document(
                "{ t: { value: \"root\"  children: [ { value: \"leaf\"  children: [] } ] } }")));
    }

    /**
     * §8's own spelling pins {@code 1..} on the recursive position <em>and</em> leaves the field REQUIRED, so
     * every node needs a child and nothing can ever be a {@code tree}. The {@code ?} the fixtures above carry
     * is the whole difference -- and it is the schema that is rejected now, not the document
     * ({@code spec/tson-rev33-changelog.md} #25).
     */
    @Test
    void theSpecsOwnTreeSpellingIsRejectedAsUninhabited() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> compile("""
                          tree => <T> { value: T  children: [tree<T>; 1..] }
                          use  => { t: tree<text> }"""));

        assertTrue(thrown.getMessage().contains("can never be satisfied"), thrown.getMessage());
    }

    // ── §8's grid fixture: one entry per form, whichever channel produced it ──

    /**
     * §8's primary grid, in the <b>record</b> spelling D10 legalised: {@code <T, N> { x: [[T; 1..N]; 2..N] }}.
     * Closing {@code grid<pixel, 3>} from two declarations derives six entries in all -- {@code grid} itself,
     * two open synthetics for the two container levels, two closed synthetics they become, and one
     * instantiation entry for the application -- and a third declaration writing the inner form directly
     * lands on the closed synthetic rather than minting a second (§8.2's cross-channel dedup).
     *
     * <p><b>This spelling is the primary fixture because it is the only one that exercises the instantiation
     * channel</b> -- see {@link #theWholeBodySpellingRecordsNoInstantiationEntry}.
     */
    @Test
    void theRecordFormGridDerivesTwoOpenSyntheticsTwoClosedAndOneInstantiation() {
        TsonCompiledSchema compiled = compile("""
                  pixel  => { r: int32 }
                  grid   => <T, N> { x: [[T; 1..N]; 2..N] }
                  a      => { g: grid<pixel, 3> }
                  b      => { g: grid<pixel, 3> }
                  direct => { d: [pixel; 1..3] }""");

        assertEquals(fieldType(compiled, "a", "g"), fieldType(compiled, "b", "g"), "one instantiation");
        assertEquals(List.of(2L, 3L), List.of(openSynthetics(compiled), closedDerived(compiled)),
                () -> "two open, three closed (two synthetics and the instantiation): " + derived(compiled));

        // grid<pixel, 3> -> { x: [[pixel; 1..3]; 2..3] }, outermost entry inwards.
        String outer = fieldType(compiled, fieldType(compiled, "a", "g"), "x");
        ArrayBody rows = assertInstanceOf(ArrayBody.class, compiled.schema().entries().get(outer).body());
        assertEquals(Optional.of(BigInteger.TWO), rows.minItems());
        assertEquals(fieldType(compiled, "direct", "d"), rows.elementType().name(),
                "the row the grid builds is the row written directly");
    }

    /**
     * The <b>whole-body</b> spelling records the application too, and needs <em>two</em> entries to do it.
     * Its closure is a closed synthetic, whose {@code source} must name the constructor it builds -- keying
     * it on the application would tie identity to the internal name of the open synthetic that produced it,
     * and would split {@code [text]} written directly from {@code [T]} closed to {@code text}. So the
     * instantiation is a separate reference entry pointing at the form.
     *
     * <p>The record spelling needs only one, because substituting a record yields a record -- structurally
     * distinct from any synthetic, so it can be the instantiation itself.
     */
    @Test
    void theWholeBodySpellingRecordsTheApplicationInAReferenceEntry() {
        TsonCompiledSchema compiled = compile("""
                  pixel => { r: int32 }
                  grid  => <T, N> [[T; N]; N]
                  a     => { g: grid<pixel, 3> }""");

        TypeDefinition instantiation = compiled.schema().entries().get(fieldType(compiled, "a", "g"));
        assertInstanceOf(Reference.class, instantiation.body());
        assertEquals("grid", instantiation.source().orElseThrow().name(), "the application, recorded");

        TypeDefinition form = formBehind(compiled, "a", "g");
        assertInstanceOf(ArrayBody.class, form.body());
        assertEquals(TypeRef.of("array"), form.source().orElseThrow(),
                "the form stays sourced to the constructor it builds");
    }

    /**
     * A generated head closing its own intermediate form mints no instantiation: nobody wrote
     * {@code array_p0_p1_p1_06c4e11f<pixel, 3>}, and an entry named for it would carry that internal name
     * into identity, which is exactly what D6 says must not happen.
     */
    @Test
    void closingAGeneratedSyntheticRecordsNoApplication() {
        TsonCompiledSchema compiled = compile("""
                  pixel => { r: int32 }
                  grid  => <T, N> [[T; N]; N]
                  a     => { g: grid<pixel, 3> }""");

        assertEquals(1, derived(compiled).stream()
                .filter(n -> compiled.schema().entries().get(n).body() instanceof Reference).count(),
                () -> "one instantiation, for the one application written: " + derived(compiled));
        assertTrue(derived(compiled).stream().noneMatch(n -> n.contains("06c4e11f_")),
                () -> "no entry named after a generated head: " + derived(compiled));
    }

    /**
     * A template that applies itself ties the knot rather than recursing. Both closure paths share one memo
     * now; before, an open instance short-circuited ahead of it and this was a {@code StackOverflowError}.
     */
    @Test
    void aTemplateApplyingItselfTiesTheKnot() {
        TsonCompiledSchema compiled = compile("""
                  weird => <T> [weird<T>]
                  use   => { w: weird<text> }""");

        String instantiation = fieldType(compiled, "use", "w");
        ArrayBody form = assertInstanceOf(ArrayBody.class, formBehind(compiled, "use", "w").body());

        assertEquals(instantiation, form.elementType().name(), "the knot");
    }

    /**
     * {@code grid => <T, N> [[T; N]; N]} closed via {@code grid<pixel, 3>} from two declarations yields
     * exactly one instantiation entry and one synthetic for the inner row -- and a third declaration writing
     * {@code [pixel; 3]} directly lands on that same synthetic (§8.2's cross-channel dedup).
     */
    @Test
    void theGridFixtureProducesOneEntryPerDistinctForm() {
        TsonCompiledSchema compiled = compile("""
                  pixel  => { r: int32 }
                  grid   => <T, N> [[T; N]; N]
                  a      => { g: grid<pixel, 3> }
                  b      => { g: grid<pixel, 3> }
                  direct => { d: [pixel; 3] }""");

        assertEquals(fieldType(compiled, "a", "g"), fieldType(compiled, "b", "g"), "one instantiation");
        ArrayBody outer = assertInstanceOf(ArrayBody.class, formBehind(compiled, "a", "g").body());
        assertEquals(fieldType(compiled, "direct", "d"), outer.elementType().name(),
                "the row the grid builds is the row written directly");
        assertEquals(Optional.of(BigInteger.valueOf(3)), outer.minItems());
    }

    /**
     * <b>Templates are consulted, never modified, by closure.</b> Closing {@code grid<pixel, 4>} after
     * {@code grid<pixel, 3>} leaves {@code grid} and both open synthetics exactly as they were and adds three
     * fresh closed entries beside the first three -- two container levels and one instantiation.
     *
     * <p>The property matters beyond tidiness: substitution walks a template's recorded open form, and a walk
     * that rewrote what it read would make the second closure depend on the first.
     */
    @Test
    void closingASecondArgumentLeavesEveryTemplateUntouched() {
        TsonCompiledSchema compiled = compile("""
                  pixel => { r: int32 }
                  grid  => <T, N> { x: [[T; 1..N]; 2..N] }
                  three => { g: grid<pixel, 3> }""");
        List<String> openBefore = openNames(compiled);

        TsonCompiledSchema both = compile("""
                  pixel => { r: int32 }
                  grid  => <T, N> { x: [[T; 1..N]; 2..N] }
                  three => { g: grid<pixel, 3> }
                  four  => { g: grid<pixel, 4> }""");

        assertEquals(List.of("T", "N"), both.schema().entries().get("grid").parameters(),
                "the declared template, unchanged");
        assertEquals(openBefore, openNames(both), "both open synthetics reused, neither rewritten");
        assertEquals(2, openSynthetics(both));
        assertEquals(6, closedDerived(both),
                () -> "three fresh closed entries per closure: " + derived(both));
    }

    /** The whole-body spelling reuses in the same way, one entry per level fewer. */
    @Test
    void theWholeBodySpellingReusesItsOpenSyntheticToo() {
        TsonCompiledSchema compiled = compile("""
                  pixel => { r: int32 }
                  grid  => <T, N> [[T; N]; N]
                  three => { g: grid<pixel, 3> }
                  four  => { g: grid<pixel, 4> }""");

        assertInstanceOf(InstanceTemplate.class, compiled.schema().entries().get("grid").body());
        assertEquals(1, openSynthetics(compiled),
                () -> "one open synthetic, shared by both closures: " + derived(compiled));
        assertEquals(6, closedDerived(compiled),
                () -> "two closed arrays and one instantiation per closure: " + derived(compiled));
    }

    /**
     * §8's smallest fixture, and Tranche D's declared stage-one target: the least a sugar form over a
     * parameter can be. One open synthetic, one closed array, one instantiation.
     */
    @Test
    void theSmallestFormNeedingAnyOfThisIsOneArrayOverOneParameter() {
        TsonCompiledSchema compiled = compile("""
                  box => <T> { a: [T] }
                  use => { u: box<text> }""");

        assertEquals(1, openSynthetics(compiled), () -> derived(compiled).toString());
        assertEquals(2, closedDerived(compiled), () -> derived(compiled).toString());
        ArrayBody array = assertInstanceOf(ArrayBody.class, compiled.schema().entries()
                .get(fieldType(compiled, fieldType(compiled, "use", "u"), "a")).body());
        assertEquals(TypeRef.of("text"), array.elementType());
    }

    // ── The nested form in miniature, and the closed case that stays out ──

    @Test
    void anArrayOfAnArrayOverAParameterClosesInnermostFirst() {
        TsonCompiledSchema compiled = compile("""
                  tmpl => <T> { a: [[T]] }
                  use  => { u: tmpl<text> }""");

        ArrayBody outer = assertInstanceOf(ArrayBody.class, compiled.schema().entries()
                .get(fieldType(compiled, fieldType(compiled, "use", "u"), "a")).body());
        ArrayBody inner = assertInstanceOf(ArrayBody.class,
                compiled.schema().entries().get(outer.elementType().name()).body());

        assertEquals(TypeRef.of("text"), inner.elementType());
    }

    /**
     * The closed counterpart, which needs no parameters anywhere: {@code [box<text>]} lifts to a synthetic
     * whose {@code element_type} is the application itself, written in {@code type_ref}'s record form, and
     * materialisation rewrites it to the instantiation entry one pass later.
     *
     * <p><b>The synthetic names something that is not an entry yet, and that is the point.</b> The window is
     * the same one an ordinary forward reference lives in -- `close()` walks every closed entry's references
     * after the driving loop, so by the time anything reads this array its element is a real name.
     */
    @Test
    void aClosedContainerHoldsAnApplicationUntilMaterialisationClosesIt() {
        TsonCompiledSchema compiled = compile("""
                  box         => <T> { a: T }
                  box_carrier => { a: [box<text>] }""");

        ArrayBody array = assertInstanceOf(ArrayBody.class, compiled.schema().entries()
                .get(fieldType(compiled, "box_carrier", "a")).body());
        TypeDefinition instantiation = compiled.schema().entries().get(array.elementType().name());

        assertEquals(List.of(), instantiation.parameters(), "closed");
        assertEquals(TypeRef.of("text"), ((RecordBody) instantiation.body()).fields().get(0).type(),
                "T bound");
        assertNotNull(compiled.get("box_carrier")
                .read(TestDocuments.document("{ a: [ { a: \"x\" } ] }")));
    }

    /** Arguments close innermost-first, so an application nested inside one needs no separate handling. */
    @Test
    void aNestedArgumentInAClosedContainerClosesInnermostFirst() {
        TsonCompiledSchema compiled = compile("""
                  pair => <T> { l: T  r: T }
                  box  => <T> { a: T }
                  deep => { d: [box<pair<int32>>] }""");

        ArrayBody array = assertInstanceOf(ArrayBody.class, compiled.schema().entries()
                .get(fieldType(compiled, "deep", "d")).body());
        String inner = fieldType(compiled, array.elementType().name(), "a");

        assertEquals(TypeRef.of("int32"),
                ((RecordBody) compiled.schema().entries().get(inner).body()).fields().get(0).type());
    }

    /**
     * A <b>value</b> argument makes the wire trip too. {@code type_argument}'s value channel binds a raw
     * {@code Token}, so the slot reads the token rather than the value it denotes -- §4 decoding would leave
     * {@code <3>} and {@code <"3">} indistinguishable, and the form is exactly what identity needs.
     */
    @Test
    void aValueArgumentInAClosedContainerKeepsItsToken() {
        TsonCompiledSchema compiled = compile("""
                  vector => <T, N> !array { element_type: T  min_items: N  max_items: N }
                  holder => { p: [vector<float32, 3>] }""");

        ArrayBody outer = assertInstanceOf(ArrayBody.class, formBehind(compiled, "holder", "p").body());
        TypeDefinition inner = compiled.schema().entries().get(outer.elementType().name());
        ArrayBody vector = assertInstanceOf(ArrayBody.class, inner.body() instanceof Reference r
                ? compiled.schema().entries().get(r.target()).body() : inner.body());

        assertEquals(Optional.of(BigInteger.valueOf(3)), vector.minItems());
        assertNotNull(compiled.get("holder")
                .read(TestDocuments.document("{ p: [ [ 1.0 2.0 3.0 ] ] }")));
        assertThrows(TsonReadException.class, () -> compiled.get("holder")
                .read(TestDocuments.document("{ p: [ [ 1.0 2.0 ] ] }")));
    }

    /**
     * And the form is what keeps two spellings apart. {@code <float32, 3>} and {@code <float32, "3">} apply
     * different arguments, so they must land on different entries -- which they do only because the token
     * reached identity with its form intact rather than decoded to the same {@code 3} twice.
     */
    @Test
    void aQuotedValueArgumentIsADifferentApplicationFromABareOne() {
        String vector = "  vector => <T, N> !array { element_type: T  min_items: N  max_items: N }\n";
        TsonCompiledSchema bare = compile(vector + "  holder => { p: [vector<float32, 3>] }");
        TsonCompiledSchema quoted = compile(vector + "  holder => { p: [vector<float32, \"3\">] }");

        assertNotEquals(fieldType(bare, "holder", "p"), fieldType(quoted, "holder", "p"),
                "one entry each, not one shared");
    }

    /** A map's value slot takes one the same way -- the table's scalar type slots are one rule, not three. */
    @Test
    void aMapValueSlotHoldsAnApplicationToo() {
        TsonCompiledSchema compiled = compile("""
                  box => <T> { a: T }
                  m   => { entries: {text => box<text>} }""");

        MapBody map = assertInstanceOf(MapBody.class, compiled.schema().entries()
                .get(fieldType(compiled, "m", "entries")).body());

        assertEquals(TypeRef.of("text"), ((RecordBody) compiled.schema().entries()
                .get(map.valueType().name()).body()).fields().get(0).type());
    }

    /** A binding whose application names no parameter is fully bound already, so it closes where it is written. */
    @Test
    void aConcreteApplicationInABindingClosesAtTheDeclaration() {
        TsonCompiledSchema compiled = compile("""
                  box     => <T> { v: T }
                  bounded => <N> !array { element_type: box<text>  min_items: N }
                  use     => { u: bounded<2> }""");

        ArrayBody closed = assertInstanceOf(ArrayBody.class, formBehind(compiled, "use", "u").body());

        assertEquals(Optional.of(BigInteger.TWO), closed.minItems());
        assertInstanceOf(RecordBody.class,
                compiled.schema().entries().get(closed.elementType().name()).body(), "box<text>, closed");
    }
}
