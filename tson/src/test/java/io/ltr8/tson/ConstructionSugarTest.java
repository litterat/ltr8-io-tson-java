package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeChain;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Construction sugar ([TSON-SCHEMA] §5.3, proposed for Revision 40): {@code C<a₁ … aₙ>} over a constructor {@code C}
 * is the construction {@code !C { f₁: a₁ … fₙ: aₙ }}, its required fields typed by a reference naming types and not
 * pinned by its chain, in declaration order -- one entry with the sugar forms {@code [T]} and {@code {K => V}}, and
 * every verdict the construction written in braces would get.
 */
class ConstructionSugarTest {

    private static final String HEADER = """
            !!id:"https://example.test/construction-sugar-%s.tn"
            !!import:"https://tson.io/2026/40/m/meta.tn"
            !!import:"https://tson.io/2026/40/m/core.tn"
            !schema {
            %s
            }
            """;

    private static String schema(String label, String body) {
        return HEADER.formatted(label, body);
    }

    private static TsonLinkedSchema resolve(String label, String body) {
        return Tson.standard().resolve(schema(label, body));
    }

    private static List<Diagnostic> problems(String label, String body) {
        return Tson.standard().validateSchema(schema(label, body));
    }

    /** The entry a record field's type names. */
    private static String fieldType(TsonLinkedSchema linked, String record, String field) {
        return ((RecordBody) linked.schema().entries().get(record)).fields().get(field).typeExpr().text();
    }

    private static void assertRefused(List<Diagnostic> problems, String fragment) {
        assertEquals(1, problems.size(), () -> "expected one diagnostic, got " + problems);
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, problems.getFirst().code(), problems::toString);
        assertTrue(problems.getFirst().message().contains(fragment), problems::toString);
    }

    @Test
    void anArrayConstructionIsTheEntryItsSugarLiftsTo() {
        TsonLinkedSchema linked = resolve("array", "  post => { a: meta.array<uuid>  b: [uuid] }");
        // A use-site construction is written in place, the same construction its sugar is, and no entry.
        String a = fieldType(linked, "post", "a");
        assertEquals(fieldType(linked, "post", "b"), a);
        assertEquals("[uuid]", a);
    }

    @Test
    void aMapConstructionTakesItsArgumentsInFieldOrder() {
        TsonLinkedSchema linked = resolve("map", "  post => { a: meta.map<text int32>  b: {text => int32} }");
        assertEquals(fieldType(linked, "post", "b"), fieldType(linked, "post", "a"));
    }

    @Test
    void aConstructionThroughAnAliasOfItsConstructorIsTheSameEntry() {
        TsonLinkedSchema linked = resolve("alias", "  list => meta.array\n  post => { a: list<uuid>  b: [uuid] }");
        assertEquals(fieldType(linked, "post", "b"), fieldType(linked, "post", "a"));
    }

    @Test
    void aNestedConstructionClosesInnermostFirst() {
        TsonLinkedSchema linked = resolve("nested",
                "  post => { a: meta.array<meta.array<uuid>>  b: [[uuid]]  c: [meta.array<uuid>] }");
        assertEquals(fieldType(linked, "post", "b"), fieldType(linked, "post", "a"));
        assertEquals(fieldType(linked, "post", "b"), fieldType(linked, "post", "c"));
    }

    /** A declaration is the construction: the value states its constructor, and no application is recorded. */
    @Test
    void aDeclaredConstructionIsTheConstruction() {
        TsonLinkedSchema linked = resolve("declared",
                "  uuid_list => meta.array<uuid>\n  written => !meta.array { element_type: uuid }");
        Top declared = linked.schema().entries().get("uuid_list");
        assertEquals(linked.schema().entries().get("written"), declared);
        assertInstanceOf(ArrayBody.class, declared);
        assertEquals(List.of(), TypeChain.supertypesOf(declared), "an instance is not IS-A its constructor");
        assertEquals(Optional.of("meta.array"), linked.schema().factsOf("uuid_list").constructor());
        assertEquals(Optional.empty(), linked.schema().applicationOf("uuid_list"));
    }

    /** A refined constructor's instance is its own kind: one entry apart from the sugar admitting the same data. */
    @Disabled("TypeInline records no constructor: a refined constructor's construction equals its source's")
    @Test
    void aRefinedConstructorAppliedIsItsOwnEntry() {
        TsonLinkedSchema linked = resolve("refined", """
                  nonempty_type => meta.array ^ { min_items?: = 1 }
                  post => { a: nonempty_type<uuid>  b: [uuid; 1..] }
                """);
        String a = fieldType(linked, "post", "a");
        String b = fieldType(linked, "post", "b");
        assertNotEquals(b, a);
        assertEquals(Optional.of("nonempty_type"), linked.schema().factsOf(a).constructor());
        assertEquals(Optional.of("meta.array"), linked.schema().factsOf(b).constructor());
        assertEquals(Optional.of(1L), ((ArrayBody) linked.schema().entries().get(a)).minItems());
    }

    /** Inside a record template the construction is held with the field and closed with each application. */
    @Test
    void aConstructionOverATemplateParameterClosesWithTheApplication() {
        TsonLinkedSchema linked = resolve("template", """
                  page      => <T> { items: meta.array<T> }
                  page_uuid => page<uuid>
                  post      => { p: page_uuid  b: [uuid] }
                """);
        String page = fieldType(linked, "post", "p");
        assertEquals(fieldType(linked, "post", "b"), fieldType(linked, page, "items"));
    }

    @Test
    void aWrongArgumentCountIsRefused() {
        assertRefused(problems("count", "  post => { a: meta.map<text> }"), "takes 2 arguments");
    }

    @Test
    void aConstructorWithNoReferenceFieldTakesNoArguments() {
        assertRefused(problems("none", "  post => { a: meta.tuple<uuid> }"), "no required field typed by a reference");
    }

    /** An argument is a reference by the grammar (§5.10), so a literal is refused where it is written. */
    @Test
    void anArgumentIsAReference() {
        List<Diagnostic> problems = problems("literal", "  post => { a: meta.array<3> }");
        assertEquals(1, problems.size(), () -> "expected one diagnostic, got " + problems);
        assertTrue(problems.getFirst().message().contains("'3' is no type argument"), problems::toString);
    }

    @Test
    void anArgumentIsJudgedByItsFieldsOwnReference() {
        assertRefused(problems("of", "  post => { a: meta.namespace<int32 text> }"), "no identifier family");
    }

    @Test
    void anIncompleteConstructionIsRefusedAsTheBracedOneIs() {
        assertRefused(problems("incomplete", "  post => { a: meta.enum_type<meta.identifier> }"),
                "missing required field 'members'");
        assertRefused(problems("incomplete-declared", "  e => meta.enum_type<meta.identifier>"),
                "missing required field 'members'");
    }

    /** A head that is no constructor, a fresh record here, still takes no arguments. */
    @Test
    void aRecordThatIsNoConstructorTakesNoArguments() {
        assertRefused(problems("record", "  bl => { v: text }\n  post => { a: bl<text> }"),
                "declares no type parameters");
    }
}
