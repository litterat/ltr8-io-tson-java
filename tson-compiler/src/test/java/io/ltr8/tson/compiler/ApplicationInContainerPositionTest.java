package io.ltr8.tson.compiler;

import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.OpenEntry;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.ParamRef;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A container position holding an <b>application</b> rather than a name -- {@code [tree<T>]}, {@code [[T]]}, {@code
 * [box<text>]}.
 *
 * <p><b>Inside an open record a container over a parameter is the reference it spells</b> ([TSON-SCHEMA] §5.10):
 * {@code [tree<T>]} is the application {@code meta.array<tree<T>>}, held as written until the record is applied, and
 * then constructed, the content-keyed entry {@code [tree<text>]} lifts to. A sized form over a parameter is no
 * reference and is refused. <b>A closed form</b> ({@code [box<text>]}) names the entry {@code box<text>} denotes,
 * which does not exist until materialisation runs a phase later: the lift writes the application as its reference,
 * and materialisation closes it.
 */
class ApplicationInContainerPositionTest {

    private static final String ID = "https://example.test/nested.tn";

    private static TsonCompiledSchema compile(String declarations) {
        String schema = """
                !!id:"https://example.test/nested.tn"
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !!import:"https://tson.io/2026/40/m/core.tn"
                !schema {
                %s
                }
                """.formatted(declarations);
        SchemaSource source = uri -> {
            if (CanonicalIdentity.sameIdentity(uri, ID)) {
                return schema;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        TsonCompiledVocabularyRegistry core =
                TsonCompiledVocabularyRegistry.withStandardLibrary(SchemaModelNameBinder.defaultContext(), source);
        return TsonCompiledSchemaRegistry.tree(core).get(ID);
    }

    private static String fieldType(TsonCompiledSchema compiled, String record, String field) {
        RecordBody body = (RecordBody) compiled.schema().entries().get(record);
        return body.fields().entrySet().stream().filter(f -> f.getKey().equals(field))
                .map(java.util.Map.Entry::getValue).findFirst().orElseThrow()
                .typeRef().name();
    }

    /**
     * The entries this schema derived rather than the author declaring them -- synthetics and instantiations, its
     * closure's own left out.
     */
    private static List<String> derived(TsonCompiledSchema compiled) {
        String own = io.ltr8.tson.base.CanonicalIdentity.canonicalize(compiled.schema().id());
        return compiled.schema().entries().entrySet().stream()
                .filter(e -> compiled.schema().positionOf(e.getKey()).isEmpty())
                .filter(e -> compiled.linkedSchema().originOf(e.getKey()).equals(own))
                .map(java.util.Map.Entry::getKey).sorted().toList();
    }

    // ── A recursive template: the knot tied through a construction ──────────

    /**
     * {@code tree => <T> { value: T  children?: [tree<T>] }} closed via {@code tree<text>}. The field holds the
     * reference {@code meta.array<tree<T>>}; closing {@code tree<text>} substitutes it to {@code
     * meta.array<tree<text>>}, whose argument reaches back to the entry still under construction, and the memo is
     * what answers: the array's {@code element_type} names the instantiation entry, recorded before it completes.
     */
    @Test
    void aRecursiveTemplateTiesTheKnotThroughTheArray() {
        TsonCompiledSchema compiled = compile("""
                  tree => <T> { value: T  children?: [tree<T>] }
                  use  => { t: tree<text> }""");

        String instantiation = fieldType(compiled, "use", "t");
        ArrayBody children = assertInstanceOf(ArrayBody.class,
                compiled.schema().entries().get(fieldType(compiled, instantiation, "children")));

        assertEquals(TypeRef.of(instantiation), children.elementType(), "the knot");
        assertEquals("tree", compiled.schema().applicationOf(instantiation).orElseThrow().name(),
                "an expansion naming itself keeps an entry recording its application");
        assertEquals(TypeRef.of("text"), ((RecordBody) compiled.schema().entries().get(instantiation))
                .fields().get("value").type(), "T bound at the value field too");
    }

    /** The template holds the array as the reference it spells, its parameter among the arguments. */
    @Test
    void theOpenRecordHoldsTheArrayAsAReference() {
        TsonCompiledSchema compiled = compile("""
                  tree => <T> { value: T  children?: [tree<T>] }""");

        RecordBody tree = assertInstanceOf(RecordBody.class, compiled.schema().entries().get("tree"));
        assertTrue(tree.isOpen());
        assertEquals(TypeRef.of("meta.array", List.of(TypeRef.of("tree", List.of(ParamRef.of("T"))))),
                tree.fields().get("children").type());
        assertTrue(derived(compiled).isEmpty(), () -> "nothing lifted for a form over a parameter: "
                + derived(compiled));
    }

    /** A recursive type terminates only if its recursive position can be empty, as this one can. */
    @Test
    void aTreeReadsRealData() {
        TsonCompiledSchema compiled = compile("""
                  tree => <T> { value: T  children: [tree<T>] }
                  use  => { t: tree<text> }""");

        assertNotNull(compiled.get("use").read(TestDocuments.document(
                "{ t: { value: \"root\"  children: [ { value: \"leaf\"  children: [] } ] } }")));
    }

    /**
     * §8's own spelling sizes the recursive position, {@code [tree<T>; 1..]}: a size over a parameter sets a field no
     * argument list carries, so the form is no reference and is refused at the declaration ([TSON-SCHEMA] §5.10).
     */
    @Test
    void aSizedArrayOverAParameterIsRefused() {
        SchemaValidationException thrown = assertThrows(SchemaValidationException.class,
                () -> compile("""
                          tree => <T> { value: T  children: [tree<T>; 1..] }
                          use  => { t: tree<text> }"""));

        assertTrue(thrown.getMessage().contains("'[tree<T>; 1..]' stands over the parameter 'T' and is no "
                + "reference"), thrown.getMessage());
    }

    // ── One entry per form, whichever channel produced it ────────────────────

    /**
     * {@code grid => <T> { x: [[T]] }} closed via {@code grid<pixel>} from two declarations derives one record and
     * the two arrays it holds, and a declaration writing {@code [[pixel]]} directly lands on the same arrays: the
     * construction and the lift name a form by its content (§8.2). Nothing records the application, an application
     * nothing declares being its expansion.
     */
    @Test
    void aHeldArrayClosesOntoTheFormItsSugarLiftsTo() {
        TsonCompiledSchema compiled = compile("""
                  pixel  => { r: int32 }
                  grid   => <T> { x: [[T]] }
                  a      => { g: grid<pixel> }
                  b      => { g: grid<pixel> }
                  direct => { d: [[pixel]] }""");

        String form = fieldType(compiled, "a", "g");
        assertEquals(form, fieldType(compiled, "b", "g"), "one expansion");
        assertEquals(fieldType(compiled, "direct", "d"), fieldType(compiled, form, "x"),
                "the rows the grid builds are the rows written directly");
        assertTrue(compiled.schema().applicationOf(form).isEmpty(), "no entry records the application");
        assertEquals(3, derived(compiled).size(), () -> "one record and two arrays: " + derived(compiled));
    }

    /**
     * <b>Templates are consulted, never modified, by closure.</b> Closing {@code grid<voxel>} after {@code
     * grid<pixel>} leaves {@code grid} exactly as it was and adds the second application's forms beside the first's.
     */
    @Test
    void closingASecondArgumentLeavesTheTemplateUntouched() {
        TsonCompiledSchema one = compile("""
                  pixel => { r: int32 }
                  voxel => { v: int32 }
                  grid  => <T> { x: [[T]] }
                  three => { g: grid<pixel> }""");
        TsonCompiledSchema both = compile("""
                  pixel => { r: int32 }
                  voxel => { v: int32 }
                  grid  => <T> { x: [[T]] }
                  three => { g: grid<pixel> }
                  four  => { g: grid<voxel> }""");

        Top grid = both.schema().entries().get("grid");
        assertEquals(List.of("T"), OpenEntry.parameterNames(grid), "the declared template, unchanged");
        assertEquals(one.schema().entries().get("grid"), grid);
        assertEquals(6, derived(both).size(), () -> "three forms per closure: " + derived(both));
    }

    /** The smallest form needing any of this: one array over one parameter, closed to an array of the argument. */
    @Test
    void theSmallestFormIsOneArrayOverOneParameter() {
        TsonCompiledSchema compiled = compile("""
                  box => <T> { a: [T] }
                  use => { u: box<uuid> }""");

        ArrayBody array = assertInstanceOf(ArrayBody.class, compiled.schema().entries()
                .get(fieldType(compiled, fieldType(compiled, "use", "u"), "a")));
        assertEquals(TypeRef.of("uuid"), array.elementType());
        assertEquals(2, derived(compiled).size(), () -> derived(compiled).toString());
    }

    // ── The nested form in miniature, and the closed case ─────────────────────

    @Test
    void anArrayOfAnArrayOverAParameterClosesInnermostFirst() {
        TsonCompiledSchema compiled = compile("""
                  tmpl => <T> { a: [[T]] }
                  use  => { u: tmpl<text> }""");

        ArrayBody outer = assertInstanceOf(ArrayBody.class, compiled.schema().entries()
                .get(fieldType(compiled, fieldType(compiled, "use", "u"), "a")));
        ArrayBody inner = assertInstanceOf(ArrayBody.class,
                compiled.schema().entries().get(outer.elementName().name()));

        assertEquals(TypeRef.of("text"), inner.elementType());
    }

    /**
     * The closed counterpart, which needs no parameters anywhere: {@code [box<text>]} lifts to a synthetic
     * whose {@code element_type} is the application itself, written as its reference, and
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
                .get(fieldType(compiled, "box_carrier", "a")));
        Top instantiation = compiled.schema().entries().get(array.elementName().name());

        assertEquals(List.of(), OpenEntry.parameterNames(instantiation), "closed");
        assertEquals(TypeRef.of("text"), ((RecordBody) instantiation).fields()
                .values().stream().toList().get(0).type(),
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
                .get(fieldType(compiled, "deep", "d")));
        String inner = fieldType(compiled, array.elementName().name(), "a");

        assertEquals(TypeRef.of("int32"),
                ((RecordBody) compiled.schema().entries().get(inner)).fields()
                        .values().stream().toList().get(0).type());
    }

    /** A map's value slot takes one the same way -- the table's scalar type slots are one rule, not three. */
    @Test
    void aMapValueSlotHoldsAnApplicationToo() {
        TsonCompiledSchema compiled = compile("""
                  box => <T> { a: T }
                  m   => { entries: {text => box<text>} }""");

        MapBody map = assertInstanceOf(MapBody.class, compiled.schema().entries()
                .get(fieldType(compiled, "m", "entries")));

        assertEquals(TypeRef.of("text"), ((RecordBody) compiled.schema().entries()
                .get(map.valueType().name())).fields().values().stream().toList().get(0).type());
    }

    /**
     * A binding whose application names no parameter is fully bound already, so it closes where it is written.
     *
     * <p>Written as sugar, which expands to a binding whose slot holds the application as the data grammar's
     * reference ([TSON-DATA] §7.4). The open and closed forms share one production, so this is the rule that
     * governs a closed instance's payload.
     */
    @Test
    void aConcreteApplicationInABindingClosesAtTheDeclaration() {
        TsonCompiledSchema compiled = compile("""
                  box     => <T> { v: T }
                  capped  => <T> { a: [box<text>; 2..]  b: T }
                  use     => { u: capped<int32> }""");

        ArrayBody closed = assertInstanceOf(ArrayBody.class,
                compiled.schema().entries().get(fieldType(compiled, fieldType(compiled, "use", "u"), "a")));

        assertEquals(Optional.of(2L), closed.minItems());
        assertInstanceOf(RecordBody.class,
                compiled.schema().entries().get(closed.elementName().name()), "box<text>, closed");
    }
}
