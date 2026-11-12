package io.ltr8.tson.compiler;

import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.TypeExpr;
import io.ltr8.tson.schema.meta.OpenEntry;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.ParamRef;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.Type;
import io.ltr8.tson.schema.meta.TypeInline;
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
 * then constructed in place. A sized form over a parameter is no reference and is refused. <b>A closed form</b>
 * ({@code [box<text>]}) is the array in place whose element holds the record {@code box<text>} closes to, in place
 * too.
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

    /** What field {@code field} of the record {@code record} holds: an entry's, or a record in place's. */
    private static TypeExpr slot(Top record, String field) {
        return ((RecordBody) record).fields().get(field).type();
    }

    /** The body a slot holds written in place. */
    private static Type inPlace(Object slot) {
        return assertInstanceOf(TypeInline.class, slot).of();
    }

    /**
     * The entries this schema holds without a position of its own, its closure's own left out: none, a form and an
     * application closing in place.
     */
    private static List<String> unpositioned(TsonCompiledSchema compiled) {
        String own = io.ltr8.tson.base.CanonicalIdentity.canonicalize(compiled.schema().id());
        return compiled.schema().entries().entrySet().stream()
                .filter(e -> compiled.schema().positionOf(e.getKey()).isEmpty())
                .filter(e -> compiled.linkedSchema().originOf(e.getKey()).equals(own))
                .map(java.util.Map.Entry::getKey).sorted().toList();
    }

    private static Top entry(TsonCompiledSchema compiled, String name) {
        return compiled.schema().entries().get(name);
    }

    // ── A recursive template: the knot tied through a construction ──────────

    /**
     * {@code tree => <T> { value: T  children?: [tree<T>] }} closed by {@code text_tree => tree<text>}. The field holds
     * the reference {@code meta.array<tree<T>>}; closing substitutes it to {@code meta.array<tree<text>>}, whose
     * argument is the application the declaration is closing, so the array's {@code element_type} names the
     * declaration (§5.10.1).
     */
    @Test
    void aRecursiveTemplateTiesTheKnotThroughTheArray() {
        TsonCompiledSchema compiled = compile("""
                  tree      => <T> { value: T  children?: [tree<T>] }
                  text_tree => tree<text>
                  use       => { t: text_tree }""");

        ArrayBody children = assertInstanceOf(ArrayBody.class, inPlace(slot(entry(compiled, "text_tree"),
                "children")));
        assertEquals(TypeRef.of("text_tree"), children.elementType(), "the knot");
        assertEquals("tree", compiled.schema().applicationOf("text_tree").orElseThrow().name());
        assertEquals(TypeRef.of("text"), slot(entry(compiled, "text_tree"), "value"), "T bound at the value field too");
    }

    /** The template holds the array in place, the application over its parameter at the element's slot. */
    @Test
    void theOpenRecordHoldsTheArrayInPlace() {
        TsonCompiledSchema compiled = compile("""
                  tree => <T> { value: T  children?: [tree<T>] }""");

        RecordBody tree = assertInstanceOf(RecordBody.class, compiled.schema().entries().get("tree"));
        assertTrue(tree.isOpen());
        assertEquals(new TypeInline(ArrayBody.of(TypeRef.of("tree", List.of(ParamRef.of("T"))))),
                tree.fields().get("children").type());
        assertTrue(unpositioned(compiled).isEmpty(), () -> "no entry added for a form over a parameter: "
                + unpositioned(compiled));
    }

    /** A recursive type terminates only if its recursive position can be empty, as this one can. */
    @Test
    void aTreeReadsRealData() {
        TsonCompiledSchema compiled = compile("""
                  tree      => <T> { value: T  children: [tree<T>] }
                  text_tree => tree<text>
                  use       => { t: text_tree }""");

        assertNotNull(compiled.get("use").read(TestDocuments.document(
                "{ t: { value: \"root\"  children: [ { value: \"leaf\"  children: [] } ] } }")));
    }

    /**
     * §8's own spelling sizes the recursive position, {@code [tree<T>; 1..]}: the array is written in place with its
     * size, and the recursion inside it ties to the declaration closing it, as an unsized one does.
     */
    @Test
    void aSizedArrayOverAParameterTiesToItsDeclaration() {
        TsonCompiledSchema compiled = compile("""
                  tree      => <T> { value: T  children?: [tree<T>; 1..] }
                  text_tree => tree<text>""");

        ArrayBody children = assertInstanceOf(ArrayBody.class, inPlace(slot(entry(compiled, "text_tree"),
                "children")));
        assertEquals(TypeRef.of("text_tree"), children.elementType());
        assertEquals(Optional.of(1L), children.minItems());
    }

    // ── One type per form, whichever channel produced it ─────────────────────

    /**
     * {@code grid => <T> { x: [[T]] }} closed by {@code grid<pixel>} at two fields closes to one type, and a field
     * writing {@code [[pixel]]} directly holds the same arrays: equal constructions in place are one type (§8.2).
     * Nothing is added to the schema, and nothing records the application.
     */
    @Test
    void aHeldArrayClosesToTheFormItsSugarWrites() {
        TsonCompiledSchema compiled = compile("""
                  pixel  => { r: int32 }
                  grid   => <T> { x: [[T]] }
                  a      => { g: grid<pixel> }
                  b      => { g: grid<pixel> }
                  direct => { d: [[pixel]] }""");

        TypeExpr a = slot(entry(compiled, "a"), "g");
        assertEquals(a, slot(entry(compiled, "b"), "g"), "one type");
        assertEquals(slot(entry(compiled, "direct"), "d"), slot(inPlace(a), "x"),
                "the rows the grid builds are the rows written directly");
        assertEquals(List.of(), unpositioned(compiled), "nothing added");
    }

    /**
     * <b>Templates are consulted, never modified, by closure.</b> Closing {@code grid<voxel>} after {@code
     * grid<pixel>} leaves {@code grid} exactly as it was, and adds nothing to the schema.
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

        Top grid = entry(both, "grid");
        assertEquals(List.of("T"), OpenEntry.parameterNames(grid), "the declared template, unchanged");
        assertEquals(entry(one, "grid"), grid);
        assertEquals(List.of(), unpositioned(both));
    }

    /** The smallest form needing any of this: one array over one parameter, closed to an array of the argument. */
    @Test
    void theSmallestFormIsOneArrayOverOneParameter() {
        TsonCompiledSchema compiled = compile("""
                  box => <T> { a: [T] }
                  use => { u: box<uuid> }""");

        ArrayBody array = assertInstanceOf(ArrayBody.class, inPlace(slot(inPlace(slot(entry(compiled, "use"), "u")),
                "a")));
        assertEquals(TypeRef.of("uuid"), array.elementType());
        assertEquals(List.of(), unpositioned(compiled));
    }

    // ── The nested form in miniature, and the closed case ─────────────────────

    @Test
    void anArrayOfAnArrayOverAParameterClosesInnermostFirst() {
        TsonCompiledSchema compiled = compile("""
                  tmpl => <T> { a: [[T]] }
                  use  => { u: tmpl<text> }""");

        ArrayBody outer = assertInstanceOf(ArrayBody.class, inPlace(slot(inPlace(slot(entry(compiled, "use"), "u")),
                "a")));
        ArrayBody inner = assertInstanceOf(ArrayBody.class, inPlace(outer.elementType()));

        assertEquals(TypeRef.of("text"), inner.elementType());
    }

    /**
     * The closed counterpart, which needs no parameters anywhere: {@code [box<text>]} is an array in place whose
     * {@code element_type} holds the record {@code box<text>} closes to, in place in turn.
     */
    @Test
    void aClosedContainerHoldsTheRecordInPlace() {
        TsonCompiledSchema compiled = compile("""
                  box         => <T> { a: T }
                  box_carrier => { a: [box<text>] }""");

        ArrayBody array = assertInstanceOf(ArrayBody.class, inPlace(slot(entry(compiled, "box_carrier"), "a")));
        RecordBody record = assertInstanceOf(RecordBody.class, inPlace(array.elementType()));

        assertEquals(List.of(), OpenEntry.parameterNames(record), "closed");
        assertEquals(TypeRef.of("text"), slot(record, "a"), "T bound");
        assertNotNull(compiled.get("box_carrier")
                .read(TestDocuments.document("{ a: [ { a: \"x\" } ] }")));
    }

    /** An application as an argument of another is no name, and is refused where it is written (§5.3). */
    @Test
    void aNestedApplicationAsAnArgumentIsRefused() {
        SchemaValidationException thrown = assertThrows(SchemaValidationException.class, () -> compile("""
                  pair => <T> { l: T  r: T }
                  box  => <T> { a: T }
                  deep => { d: [box<pair<int32>>] }"""));

        assertTrue(thrown.getMessage().contains("'pair<int32>' is passed as an argument of 'box<pair<int32>>'"),
                thrown.getMessage());
    }

    /** A map's value slot takes one the same way -- the type slots are one rule, not three. */
    @Test
    void aMapValueSlotHoldsAnApplicationToo() {
        TsonCompiledSchema compiled = compile("""
                  box => <T> { a: T }
                  m   => { entries: {text => box<text>} }""");

        MapBody map = assertInstanceOf(MapBody.class, inPlace(slot(entry(compiled, "m"), "entries")));

        assertEquals(TypeRef.of("text"), slot(inPlace(map.valueType()), "a"));
    }

    /**
     * A binding whose application names no parameter is fully bound already, so it closes where it is written, in
     * the open record's own declaration: {@code [box<text>; 2..]} is the sized array in place, holding the record in
     * place.
     */
    @Test
    void aConcreteApplicationInABindingClosesAtTheDeclaration() {
        TsonCompiledSchema compiled = compile("""
                  box     => <T> { v: T }
                  capped  => <T> { a: [box<text>; 2..]  b: T }
                  use     => { u: capped<int32> }""");

        ArrayBody closed = assertInstanceOf(ArrayBody.class, inPlace(slot(entry(compiled, "capped"), "a")));

        assertEquals(Optional.of(2L), closed.minItems());
        assertInstanceOf(RecordBody.class, inPlace(closed.elementType()), "box<text>, closed in place");
        assertEquals(slot(entry(compiled, "capped"), "a"), slot(inPlace(slot(entry(compiled, "use"), "u")), "a"),
                "every application holds the same type");
    }
}
