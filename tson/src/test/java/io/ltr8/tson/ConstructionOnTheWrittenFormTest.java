package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.compiler.TsonDocumentPeek;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.ApplyBody;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.ParamRef;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeExpr;
import io.ltr8.tson.schema.meta.TypeInline;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.suite.Sidecar;
import io.ltr8.tson.suite.Vectors;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A constructor's application is its construction on the written form, before anything reads it ([TSON-SCHEMA] §5.3,
 * §5.10): {@code meta.map<text meta.array<box<T>>>} is {@code {text => [box<T>]}}, read once, every application left
 * an open record's over names, so nothing stands in for an argument written in place.
 */
class ConstructionOnTheWrittenFormTest {

    private static final String SCHEMA = """
            !!id:"https://example.test/written-form-%s.tn"
            !!import:"https://tson.io/2026/40/m/meta.tn"
            !schema {
              box => <T> { v: T }
            %s
            }
            """;

    private static String schema(String label, String body) {
        return SCHEMA.formatted(label, body);
    }

    private static TsonLinkedSchema resolve(String label, String body) {
        Tson tson = Tson.standard();
        assertEquals(List.of(), tson.validateSchema(schema(label + "-check", body)));
        return tson.resolve(schema(label, body));
    }

    private static List<Diagnostic> problems(String label, String body) {
        return Tson.standard().validateSchema(schema(label, body));
    }

    private static TypeExpr fieldType(TsonLinkedSchema linked, String record, String field) {
        return ((RecordBody) linked.schema().entries().get(record)).fields().get(field).type();
    }

    private static void refused(String label, String body, String fragment) {
        List<Diagnostic> problems = problems(label, body);
        assertEquals(1, problems.size(), problems::toString);
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, problems.getFirst().code(), problems::toString);
        assertTrue(problems.getFirst().message().contains(fragment), problems.getFirst().message());
    }

    /** {@code x => [meta.array<box<text>>]} is the sugar a field writes, and resolves to what the field holds. */
    @Test
    void aSugarBodiedDeclarationHoldingAConstructorsApplicationIsTheFieldSpelling() {
        TsonLinkedSchema linked = resolve("sugar-body", """
                  x => [meta.array<box<text>>]
                  k => { f: [meta.array<box<text>>] }""");

        Top declared = linked.schema().entries().get("x");
        TypeInline field = assertInstanceOf(TypeInline.class, fieldType(linked, "k", "f"));
        assertEquals(field.of(), declared);
        ArrayBody inner = assertInstanceOf(ArrayBody.class,
                assertInstanceOf(TypeInline.class, ((ArrayBody) declared).elementType()).of());
        assertInstanceOf(RecordBody.class, assertInstanceOf(TypeInline.class, inner.elementType()).of());
    }

    /**
     * A constructor's application three deep is constructed innermost first, in a closed record and in an open one,
     * and the open record's application closes to what the closed record holds.
     */
    @Test
    void aConstructorsApplicationThreeDeepClosesWhenItsOpenRecordIsApplied() {
        TsonLinkedSchema linked = resolve("three-deep", """
                  k  => { f: meta.map<text meta.array<meta.array<box<text>>>> }
                  s  => { f: {text => [[box<text>]]} }
                  w  => <T> { f: meta.map<text meta.array<meta.array<box<T>>>> }
                  wt => w<text>""");

        assertEquals(fieldType(linked, "s", "f"), fieldType(linked, "k", "f"));
        assertEquals(fieldType(linked, "k", "f"), fieldType(linked, "wt", "f"));
        MapBody open = assertInstanceOf(MapBody.class, ((TypeInline) fieldType(linked, "w", "f")).of());
        ArrayBody outer = assertInstanceOf(ArrayBody.class, ((TypeInline) open.valueType()).of());
        ArrayBody inner = assertInstanceOf(ArrayBody.class, ((TypeInline) outer.elementType()).of());
        assertEquals(new ApplyBody(TypeRef.of("box"), List.of(ParamRef.of("T"))).inPlace(), inner.elementType());
    }

    /** {@code { of: !meta.apply { head: meta.array  arguments: [text] } }} means what {@code [text]} means (§5.10). */
    @Test
    void anExplicitApplicationOverAConstructorStillConstructs() {
        TsonLinkedSchema linked = resolve("explicit", """
                  g  => { f: [text] }
                  e  => !meta.record { fields: { f => { type: { of: !meta.apply { head: meta.array \
                arguments: [text] } } } } }
                  o  => <T> !meta.record { fields: { f => { type: { of: !meta.apply { head: meta.array \
                arguments: [T] } } } } }
                  ot => o<text>""");

        assertEquals(fieldType(linked, "g", "f"), fieldType(linked, "e", "f"));
        assertEquals(fieldType(linked, "g", "f"), fieldType(linked, "ot", "f"));
    }

    /** The kernel types {@code meta.apply.arguments} as names, so an explicit one holding a type in place is refused. */
    @Test
    void anExplicitApplicationWithAnArgumentInPlaceIsStillRefused() {
        refused("explicit-in-place", """
                  e => !meta.record { fields: { f => { type: { of: !meta.apply { head: meta.array  arguments: [{ of: \
                !meta.apply { head: box  arguments: [text] } }] } } } } }""", "expected a type's name, found a record");
    }

    /** An application is written in place, never as a declaration's body, whatever its head (§5.10). */
    @Test
    void anApplicationAsADeclarationsBodyIsRefusedWhereItResolves() {
        refused("apply-open", "  weird => !meta.apply { head: box  arguments: [text] }",
                "declare it as 'weird => box<text>'");
        refused("apply-constructor", "  weird => !meta.apply { head: meta.array  arguments: [text] }",
                "declare it as 'weird => meta.array<text>'");
    }

    /** No resolved output and no diagnostic over the corpus's schema documents names a stand-in for an argument. */
    @TestFactory
    Stream<DynamicTest> noCorpusSchemaMeetsAStandIn() {
        return Stream.of("schema", "link").flatMap(layer -> Vectors.in("class2", layer,
                ConstructionOnTheWrittenFormTest::noStandIn));
    }

    private static void noStandIn(String bucket, Path subject, io.ltr8.tson.compiler.ast.RecordValue sidecar)
            throws java.io.IOException {
        Tson tson = Class2ConformanceSuiteTest.newTson();
        String source = Sidecar.subjectSource(subject);
        List<Diagnostic> problems = tson.validateSchema(source);
        assertFalse(problems.toString().contains("in_place_"), problems::toString);
        Optional<String> id = TsonDocumentPeek.of(source).header().id();
        if (problems.isEmpty() && id.isPresent()) {
            String rendered = ResolvedForm.ownEntries(tson, id.get()).toString();
            assertFalse(rendered.contains("in_place_"), rendered);
        }
    }
}
