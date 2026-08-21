package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonCanonicalIdentity;
import io.ltr8.tson.schema.TsonSchemaValidationException;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.InstanceTemplate;
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
                !!meta:"https://tson.io/2026/32/m/meta.tn"
                !!import:"https://tson.io/2026/32/m/core.tn"
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
     * ({@code SPEC-FEEDBACK.md} #25).
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
     * The <b>whole-body</b> spelling, and the one difference between the two that is visible in resolver
     * output: it records <em>no instantiation entry at all</em>. Closing it goes through the {@code
     * instance_template} path, which names the entry it produces for the <b>form</b> -- so the field points
     * straight at the array, and nothing anywhere records that {@code grid<pixel, 3>} was written.
     *
     * <p>Both halves follow D6 and neither is a defect: a closed synthetic's {@code source} names the
     * constructor it builds, an instantiation entry's names the application it closes. The consequence is
     * worth pinning rather than discovering -- two spellings of what an author would call one type differ in
     * whether the application survives §8 output.
     */
    @Test
    void theWholeBodySpellingRecordsNoInstantiationEntry() {
        TsonCompiledSchema compiled = compile("""
                  pixel => { r: int32 }
                  grid  => <T, N> [[T; N]; N]
                  a     => { g: grid<pixel, 3> }""");

        TypeDefinition entry = compiled.schema().entries().get(fieldType(compiled, "a", "g"));

        assertInstanceOf(ArrayBody.class, entry.body(), "the array itself, not a reference to one");
        assertEquals(TypeRef.of("array"), entry.source().orElseThrow(),
                "sourced to the constructor it builds, so the application is not recorded anywhere");
        assertEquals(0, derived(compiled).stream()
                .filter(n -> compiled.schema().entries().get(n).source()
                        .filter(s -> s.name().equals("grid")).isPresent()).count(),
                () -> "no entry sourced to grid<...>: " + derived(compiled));
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
        ArrayBody outer = assertInstanceOf(ArrayBody.class,
                compiled.schema().entries().get(fieldType(compiled, "a", "g")).body());
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
        assertEquals(4, closedDerived(compiled),
                () -> "two closed arrays per closure, none shared: " + derived(compiled));
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
     * The closed counterpart stays refused, and the reason is not the one it used to give. The form lifts
     * fine; what cannot carry it is the wire, because a {@code type_ref}'s {@code arguments} has no compiled
     * reader -- {@code type_argument} is a field-group record in the kernel and a sealed interface here.
     */
    @Test
    void aClosedContainerCannotHoldAnApplication() {
        UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class,
                () -> compile("""
                          box => <T> { v: T }
                          xs  => [box<text>]"""));

        assertTrue(thrown.getMessage().contains("no compiled reader"), thrown.getMessage());
    }

    /** A binding whose application names no parameter is fully bound already, so it closes where it is written. */
    @Test
    void aConcreteApplicationInABindingClosesAtTheDeclaration() {
        TsonCompiledSchema compiled = compile("""
                  box     => <T> { v: T }
                  bounded => <N> !array { element_type: box<text>  min_items: N }
                  use     => { u: bounded<2> }""");

        ArrayBody closed = assertInstanceOf(ArrayBody.class,
                compiled.schema().entries().get(fieldType(compiled, "use", "u")).body());

        assertEquals(Optional.of(BigInteger.TWO), closed.minItems());
        assertInstanceOf(RecordBody.class,
                compiled.schema().entries().get(closed.elementType().name()).body(), "box<text>, closed");
    }
}
