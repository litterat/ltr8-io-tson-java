package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.stream.AbsentEvent;
import io.ltr8.tson.compiler.stream.AnnotationEnd;
import io.ltr8.tson.compiler.stream.AnnotationStart;
import io.ltr8.tson.compiler.stream.ArrayEnd;
import io.ltr8.tson.compiler.stream.ArrayStart;
import io.ltr8.tson.compiler.stream.DocumentEnd;
import io.ltr8.tson.compiler.stream.DocumentStart;
import io.ltr8.tson.compiler.stream.EmptyBraceEvent;
import io.ltr8.tson.compiler.stream.FieldName;
import io.ltr8.tson.compiler.stream.MapArrow;
import io.ltr8.tson.compiler.stream.MapEnd;
import io.ltr8.tson.compiler.stream.MapStart;
import io.ltr8.tson.compiler.stream.RecordEnd;
import io.ltr8.tson.compiler.stream.RecordStart;
import io.ltr8.tson.compiler.stream.SchemaRef;
import io.ltr8.tson.compiler.stream.TokenEvent;
import io.ltr8.tson.compiler.stream.TsonEvent;
import io.ltr8.tson.compiler.stream.TypeRef;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TsonDataStreamTest {

    private static List<TsonEvent> events(String source) {
        List<TsonEvent> list = new ArrayList<>();
        TsonDataStream stream = new TsonDataStream(source);
        while (stream.hasNext()) {
            list.add(stream.next());
        }
        return list;
    }

    /** A compact, order-and-content-sensitive shape for a whole event sequence, for exact assertions. */
    private static List<String> shape(String source) {
        return events(source).stream().map(TsonDataStreamTest::describe).toList();
    }

    private static String describe(TsonEvent e) {
        return switch (e) {
            case DocumentStart d -> "DocumentStart(" + d.id().orElse("") + "|" + d.schema().orElse("") + ")";
            case DocumentEnd d -> "DocumentEnd";
            case RecordStart r -> "RecordStart";
            case FieldName f -> "FieldName(" + f.name() + ")";
            case RecordEnd r -> "RecordEnd";
            case MapStart m -> "MapStart";
            case MapArrow m -> "MapArrow";
            case MapEnd m -> "MapEnd";
            case ArrayStart a -> "ArrayStart";
            case ArrayEnd a -> "ArrayEnd";
            case AnnotationStart a -> "AnnotationStart(" + a.name() + ")";
            case AnnotationEnd a -> "AnnotationEnd";
            case TypeRef t -> "TypeRef(" + t.name() + ")";
            case SchemaRef s -> "SchemaRef(" + s.uri() + ")";
            case TokenEvent t -> "Token(" + t.text() + "," + t.form() + ")";
            case AbsentEvent a -> "Absent";
            case EmptyBraceEvent e2 -> "EmptyBrace";
        };
    }

    // ── Bare tokens as the whole document ───────────────────────────────

    @Test
    void unquotedTokenRoot() {
        assertEquals(List.of("DocumentStart(|)", "Token(Alice,UNQUOTED)", "DocumentEnd"), shape("Alice"));
    }

    @Test
    void quotedTokenRoot() {
        assertEquals(List.of("DocumentStart(|)", "Token(has spaces,SINGLE_LINE_QUOTED)", "DocumentEnd"),
                shape("\"has spaces\""));
    }

    @Test
    void absentRoot() {
        assertEquals(List.of("DocumentStart(|)", "Absent", "DocumentEnd"), shape("_"));
    }

    @Test
    void emptyBraceRoot() {
        assertEquals(List.of("DocumentStart(|)", "EmptyBrace", "DocumentEnd"), shape("{}"));
        assertEquals(List.of("DocumentStart(|)", "EmptyBrace", "DocumentEnd"), shape("{   }"));
    }

    // ── Document header ──────────────────────────────────────────────────

    @Test
    void idDirectiveOnly() {
        List<TsonEvent> es = events("!!id:\"https://example.com/x.tn1\"\n_");
        DocumentStart start = (DocumentStart) es.get(0);
        assertEquals("https://example.com/x.tn1", start.id().orElseThrow());
        assertTrue(start.schema().isEmpty());
    }

    @Test
    void idAndSchemaDirectives() {
        List<TsonEvent> es = events("""
                !!id:"https://example.com/orders/1042.tn1"
                !!schema:"https://example.com/order.tn1"
                Alice
                """);
        DocumentStart start = (DocumentStart) es.get(0);
        assertEquals("https://example.com/orders/1042.tn1", start.id().orElseThrow());
        assertEquals("https://example.com/order.tn1", start.schema().orElseThrow());
        assertEquals("Token(Alice,UNQUOTED)", describe(es.get(1)));
    }

    @Test
    void schemaDirectiveWithoutId() {
        List<TsonEvent> es = events("!!schema:\"https://example.com/order.tn1\" Alice");
        DocumentStart start = (DocumentStart) es.get(0);
        assertTrue(start.id().isEmpty());
        assertEquals("https://example.com/order.tn1", start.schema().orElseThrow());
    }

    @Test
    void metaDirectiveIsRejectedAsSchemaDocument() {
        TsonDataStream stream = new TsonDataStream("!!meta:\"https://example.com/m.tn1\" { }");
        assertThrows(TsonUnsupportedDocumentException.class, stream::hasNext);
    }

    @Test
    void idThenMetaIsRejectedAsSchemaDocument() {
        TsonDataStream stream = new TsonDataStream(
                "!!id:\"https://example.com/x.tn1\"\n!!meta:\"https://example.com/m.tn1\" { }");
        assertThrows(TsonUnsupportedDocumentException.class, stream::hasNext);
    }

    @Test
    void unexpectedContentAfterRootValueIsParseError() {
        TsonDataStream stream = new TsonDataStream("Alice Bob");
        assertThrows(TsonParseException.class, () -> {
            while (stream.hasNext()) {
                stream.next();
            }
        });
    }

    // ── Records ──────────────────────────────────────────────────────────

    @Test
    void recordWithOneField() {
        assertEquals(List.of("DocumentStart(|)", "RecordStart", "FieldName(name)",
                "Token(Alice,UNQUOTED)", "RecordEnd", "DocumentEnd"), shape("{ name: Alice }"));
    }

    @Test
    void recordNoSeparatorNeededAroundBraces() {
        assertEquals(shape("{ name: Alice }"), shape("{name:Alice}"));
    }

    @Test
    void recordWithMultipleFieldsCommaAndWhitespaceSeparators() {
        assertEquals(List.of("DocumentStart(|)", "RecordStart",
                "FieldName(a)", "Token(1,UNQUOTED)",
                "FieldName(b)", "Token(2,UNQUOTED)",
                "FieldName(c)", "Token(3,UNQUOTED)",
                "RecordEnd", "DocumentEnd"), shape("{ a: 1, b: 2 c: 3 }"));
    }

    @Test
    void recordFieldNameCanBeQuoted() {
        List<TsonEvent> es = events("{ \"name\": Alice }");
        assertEquals("FieldName(name)", describe(es.get(2)));
    }

    @Test
    void recordFieldOrderAndDuplicatesArePreservedNotDeduplicated() {
        assertEquals(List.of("DocumentStart(|)", "RecordStart",
                "FieldName(x)", "Token(1,UNQUOTED)",
                "FieldName(x)", "Token(2,UNQUOTED)",
                "RecordEnd", "DocumentEnd"), shape("{ x: 1 x: 2 }"));
    }

    @Test
    void nestedRecord() {
        assertEquals(List.of("DocumentStart(|)", "RecordStart", "FieldName(customer)",
                "RecordStart", "FieldName(name)", "Token(Alice,UNQUOTED)", "RecordEnd",
                "RecordEnd", "DocumentEnd"), shape("{ customer: { name: Alice } }"));
    }

    @Test
    void recordFieldValueCanBeAbsent() {
        assertEquals(List.of("DocumentStart(|)", "RecordStart", "FieldName(x)", "Absent",
                "RecordEnd", "DocumentEnd"), shape("{ x: _ }"));
    }

    @Test
    void trailingCommaInRecordIsParseError() {
        assertThrows(TsonParseException.class, () -> shape("{ x: 1, }"));
    }

    /**
     * The data path shares {@code expect}/{@code describe} with the schema path, so it gets the same
     * construct-led wording and the same structured {@code expected}/{@code actual} pair (issue #29). Pinned
     * here because Part 1's accept/reject set is frozen and only the wording may move.
     */
    @Test
    void aMismatchNamesTheConstructAndCarriesItStructurally() {
        TsonParseException thrown = assertThrows(TsonParseException.class, () -> shape("{ a: 1  b 2 }"));
        assertEquals("expected a record field's ':', found '2'", thrown.getMessage());
        assertEquals("a record field's ':'", thrown.expected());
        assertEquals("'2'", thrown.actual());
    }

    @Test
    void contentAfterTheDocumentsValueIsAParseErrorFromTheStreamItself() {
        // RootFrame rejects it before ever emitting DocumentEnd, so no reader has to police trailing
        // content on top -- a whole-document read reaching DocumentEnd has already been guaranteed it.
        TsonParseException thrown = assertThrows(TsonParseException.class, () -> shape("{ a: 1 } junk"));
        assertTrue(thrown.getMessage().contains("unexpected content after the document's value"), thrown::getMessage);
    }

    @Test
    void zeroWidthSeparationBetweenFieldsIsParseError() {
        assertThrows(TsonParseException.class, () -> shape("{ a: \"x\"b: \"y\" }"));
    }

    @Test
    void annotatedValueAsAttemptedFieldNameIsParseError() {
        assertThrows(TsonParseException.class, () -> shape("{ @deprecated x: 1 }"));
    }

    @Test
    void typedValueAsAttemptedFieldNameIsParseError() {
        assertThrows(TsonParseException.class, () -> shape("{ !string x: 1 }"));
    }

    // ── Maps ─────────────────────────────────────────────────────────────

    @Test
    void mapWithOneEntry() {
        assertEquals(List.of("DocumentStart(|)", "MapStart", "Token(WELCOME10,UNQUOTED)", "MapArrow",
                "Token(10%,SINGLE_LINE_QUOTED)", "MapEnd", "DocumentEnd"),
                shape("{ WELCOME10 => \"10%\" }"));
    }

    @Test
    void mapWithMultipleEntries() {
        assertEquals(List.of("DocumentStart(|)", "MapStart",
                "Token(WELCOME10,UNQUOTED)", "MapArrow", "Token(10%,SINGLE_LINE_QUOTED)",
                "Token(loyalty,UNQUOTED)", "MapArrow", "Absent",
                "MapEnd", "DocumentEnd"), shape("{ WELCOME10 => \"10%\" loyalty => _ }"));
    }

    @Test
    void trailingCommaInMapIsParseError() {
        assertThrows(TsonParseException.class, () -> shape("{ a => 1, }"));
    }

    // ── The {} record/map lookahead heuristic: one token settles it for @ ! { [ _ ───

    @Test
    void mapKeyStartingWithAnnotationIsAlwaysMapStart() {
        assertEquals(List.of("DocumentStart(|)", "MapStart",
                "AnnotationStart(deprecated)", "AnnotationEnd", "Token(key,UNQUOTED)",
                "MapArrow", "Token(1,UNQUOTED)", "MapEnd", "DocumentEnd"),
                shape("{ @deprecated key => 1 }"));
    }

    @Test
    void mapKeyCanCarryAnnotationsAndTypeRef() {
        assertEquals(List.of("DocumentStart(|)", "MapStart",
                "AnnotationStart(deprecated)", "AnnotationEnd", "TypeRef(string)", "Token(key,UNQUOTED)",
                "MapArrow", "Token(1,UNQUOTED)", "MapEnd", "DocumentEnd"),
                shape("{ @deprecated !string key => 1 }"));
    }

    @Test
    void mapKeyStartingWithTypeRefIsAlwaysMapStart() {
        assertEquals(List.of("DocumentStart(|)", "MapStart",
                "TypeRef(int)", "Token(5,UNQUOTED)", "MapArrow", "Token(1,UNQUOTED)",
                "MapEnd", "DocumentEnd"), shape("{ !int 5 => 1 }"));
    }

    @Test
    void mapKeyStartingWithNestedBraceIsAlwaysMapStart() {
        assertEquals(List.of("DocumentStart(|)", "MapStart",
                "RecordStart", "FieldName(a)", "Token(1,UNQUOTED)", "RecordEnd",
                "MapArrow", "Token(x,SINGLE_LINE_QUOTED)", "MapEnd", "DocumentEnd"),
                shape("{ { a: 1 } => \"x\" }"));
    }

    @Test
    void mapKeyStartingWithArrayIsAlwaysMapStart() {
        assertEquals(List.of("DocumentStart(|)", "MapStart",
                "ArrayStart", "Token(1,UNQUOTED)", "Token(2,UNQUOTED)", "ArrayEnd",
                "MapArrow", "Token(x,UNQUOTED)", "MapEnd", "DocumentEnd"),
                shape("{ [1 2] => x }"));
    }

    @Test
    void absentAsMapKeyParsesStructurally() {
        assertEquals(List.of("DocumentStart(|)", "MapStart", "Absent", "MapArrow", "Token(1,UNQUOTED)",
                "MapEnd", "DocumentEnd"), shape("{ _ => 1 }"));
    }

    // ── Arrays ───────────────────────────────────────────────────────────

    @Test
    void emptyArray() {
        assertEquals(List.of("DocumentStart(|)", "ArrayStart", "ArrayEnd", "DocumentEnd"), shape("[]"));
        assertEquals(List.of("DocumentStart(|)", "ArrayStart", "ArrayEnd", "DocumentEnd"), shape("[   ]"));
    }

    @Test
    void arrayWithElements() {
        assertEquals(List.of("DocumentStart(|)", "ArrayStart",
                "Token(1,UNQUOTED)", "Token(2,UNQUOTED)", "Token(3,UNQUOTED)",
                "ArrayEnd", "DocumentEnd"), shape("[1 2 3]"));
    }

    @Test
    void arrayCommaSeparated() {
        assertEquals(shape("[1 2 3]"), shape("[1, 2, 3]"));
    }

    @Test
    void absentOccupiesPositionalArraySlot() {
        assertEquals(List.of("DocumentStart(|)", "ArrayStart",
                "Token(1,UNQUOTED)", "Absent", "Token(3,UNQUOTED)",
                "ArrayEnd", "DocumentEnd"), shape("[1 _ 3]"));
    }

    @Test
    void nestedArraysAndRecords() {
        assertEquals(List.of("DocumentStart(|)", "ArrayStart",
                "RecordStart", "FieldName(sku)", "Token(A-100,UNQUOTED)", "RecordEnd",
                "RecordStart", "FieldName(sku)", "Token(B-205,UNQUOTED)", "RecordEnd",
                "ArrayEnd", "DocumentEnd"), shape("[ { sku: A-100 } { sku: B-205 } ]"));
    }

    @Test
    void trailingCommaInArrayIsParseError() {
        assertThrows(TsonParseException.class, () -> shape("[1, 2, 3,]"));
    }

    @Test
    void zeroWidthSeparationInArrayIsParseError() {
        assertThrows(TsonParseException.class, () -> shape("[{a:1}{b:2}]"));
    }

    @Test
    void arrayElementCanHaveOwnSchemaDirective() {
        assertEquals(List.of("DocumentStart(|)", "ArrayStart",
                "SchemaRef(https://example.com/s.tn1)", "Token(1,UNQUOTED)", "Token(2,UNQUOTED)",
                "ArrayEnd", "DocumentEnd"),
                shape("[ !!schema:\"https://example.com/s.tn1\" 1 2 ]"));
    }

    // ── Unterminated structures must fail fast, not hang (bounded lookahead, no infinite loop) ──

    @Test
    void unterminatedArrayIsParseErrorNotHang() {
        assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> assertThrows(TsonParseException.class, () -> shape("[1, 2, 3")));
    }

    @Test
    void unterminatedRecordIsParseErrorNotHang() {
        assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> assertThrows(TsonParseException.class, () -> shape("{ x: 1")));
    }

    @Test
    void unterminatedNestedStructureIsParseErrorNotHang() {
        assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> assertThrows(TsonParseException.class, () -> shape("{ x: [1 2")));
    }

    // ── Type annotations (§3.2) ──────────────────────────────────────────

    @Test
    void typeAnnotationOnRoot() {
        assertEquals(List.of("DocumentStart(|)", "TypeRef(uuid)",
                        "Token(9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09,UNQUOTED)", "DocumentEnd"),
                shape("!uuid 9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09"));
    }

    @Test
    void typeAnnotationDirectlyBeforeBraceNoSpaceNeeded() {
        assertEquals(List.of("DocumentStart(|)", "TypeRef(person)", "RecordStart",
                "FieldName(name)", "Token(Alice,UNQUOTED)", "RecordEnd", "DocumentEnd"),
                shape("!person{name:Alice}"));
    }

    @Test
    void typeAnnotationMissingSpaceBeforeQuotedTokenIsParseError() {
        assertThrows(TsonParseException.class, () -> shape("!int32\"5\""));
    }

    @Test
    void bangNotAdjacentToTypeNameIsParseError() {
        assertThrows(TsonParseException.class, () -> shape("! person Alice"));
    }

    // ── Annotations (§3.1) ───────────────────────────────────────────────

    @Test
    void valuelessAnnotation() {
        assertEquals(List.of("DocumentStart(|)", "AnnotationStart(deprecated)", "AnnotationEnd",
                "Token(GOLD,UNQUOTED)", "DocumentEnd"), shape("@deprecated GOLD"));
    }

    @Test
    void annotationWithValue() {
        assertEquals(List.of("DocumentStart(|)", "AnnotationStart(expires)",
                "Token(2026-12-31,SINGLE_LINE_QUOTED)", "AnnotationEnd",
                "Token(GOLD,UNQUOTED)", "DocumentEnd"), shape("@expires:\"2026-12-31\" GOLD"));
    }

    @Test
    void multipleAnnotationsPreserveOrder() {
        assertEquals(List.of("DocumentStart(|)",
                "AnnotationStart(a)", "AnnotationEnd",
                "AnnotationStart(b)", "AnnotationEnd",
                "Token(value,UNQUOTED)", "DocumentEnd"), shape("@a @b value"));
    }

    @Test
    void annotationValueCanItselfBeAContainer() {
        assertEquals(List.of("DocumentStart(|)", "AnnotationStart(meta)",
                "RecordStart", "FieldName(k)", "Token(v,UNQUOTED)", "RecordEnd", "AnnotationEnd",
                "Token(value,UNQUOTED)", "DocumentEnd"), shape("@meta:{k: v} value"));
    }

    // ── A larger, combined smoke test over many constructs at once ───────

    @Test
    void combinedSmokeTestDoesNotThrowAndBalancesEveryContainer() {
        String source = """
                !!id:"https://example.com/orders/1.tn1"
                {
                  customer: @verified !string "Alice"
                  tags: [ premium _ "gold" ]
                  discounts: { WELCOME10 => "10%" @deprecated legacy => "5%" }
                  meta: {}
                  nested: { @deprecated !int key => { a: 1 b: [1 2 3] } }
                }
                """;
        List<TsonEvent> es = events(source);
        assertFalse(es.isEmpty());
        assertEquals("DocumentEnd", describe(es.get(es.size() - 1)));

        int depth = 0;
        for (TsonEvent e : es) {
            depth += switch (e) {
                case RecordStart r -> 1;
                case MapStart m -> 1;
                case ArrayStart a -> 1;
                case RecordEnd r -> -1;
                case MapEnd m -> -1;
                case ArrayEnd a -> -1;
                default -> 0;
            };
            assertTrue(depth >= 0, "container depth must never go negative");
        }
        assertEquals(0, depth, "every opened container must be closed exactly once");
    }
}
