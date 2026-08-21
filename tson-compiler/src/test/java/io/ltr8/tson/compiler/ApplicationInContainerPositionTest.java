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

    /** Closing a second argument reuses the template untouched and adds fresh closed entries beside the first. */
    @Test
    void closingASecondArgumentLeavesTheTemplatesUntouched() {
        TsonCompiledSchema compiled = compile("""
                  pixel => { r: int32 }
                  grid  => <T, N> [[T; N]; N]
                  three => { g: grid<pixel, 3> }
                  four  => { g: grid<pixel, 4> }""");

        assertInstanceOf(InstanceTemplate.class, compiled.schema().entries().get("grid").body());
        assertEquals(List.of("T", "N"), compiled.schema().entries().get("grid").parameters());
        assertEquals(1, derived(compiled).stream()
                .filter(n -> compiled.schema().entries().get(n).body() instanceof InstanceTemplate).count(),
                () -> "one open synthetic, shared by both closures: " + derived(compiled));
        assertEquals(4, derived(compiled).stream()
                .filter(n -> compiled.schema().entries().get(n).body() instanceof ArrayBody).count(),
                () -> "two closed arrays per closure, none shared: " + derived(compiled));
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
