package io.ltr8.tson.compiler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TsonDataEmitterTest {

    @Test
    void emptyRecord() {
        assertEquals("{}", new TsonDataEmitter().beginRecord().endRecord().toString());
    }

    @Test
    void emptyArray() {
        assertEquals("[]", new TsonDataEmitter().beginArray().endArray().toString());
    }

    @Test
    void simpleRecord() {
        String tson = new TsonDataEmitter()
                .beginRecord()
                .field("x").unquotedToken("1")
                .field("y").unquotedToken("2")
                .endRecord()
                .toString();
        assertEquals("{ x: 1 y: 2 }", tson);
    }

    @Test
    void simpleArray() {
        String tson = new TsonDataEmitter()
                .beginArray()
                .beforeArrayElement().unquotedToken("1")
                .beforeArrayElement().unquotedToken("2")
                .beforeArrayElement().unquotedToken("3")
                .endArray()
                .toString();
        assertEquals("[ 1 2 3 ]", tson);
    }

    @Test
    void simpleMapEntry() {
        String tson = new TsonDataEmitter()
                .beginMap()
                .beforeMapEntry().unquotedToken("WELCOME10").mapArrow().quotedString("10%")
                .endMap()
                .toString();
        assertEquals("{ WELCOME10 => \"10%\" }", tson);
    }

    @Test
    void nestedRecordInArray() {
        String tson = new TsonDataEmitter()
                .beginArray()
                .beforeArrayElement()
                .beginRecord().field("x").unquotedToken("1").endRecord()
                .beforeArrayElement()
                .beginRecord().field("x").unquotedToken("2").endRecord()
                .endArray()
                .toString();
        assertEquals("[ { x: 1 } { x: 2 } ]", tson);
    }

    @Test
    void typeRefBeforeValue() {
        String tson = new TsonDataEmitter().typeRef("uuid").quotedString("9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09").toString();
        assertEquals("!uuid \"9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09\"", tson);
    }

    @Test
    void quotedStringEscapesQuoteAndBackslash() {
        assertEquals("\"a\\\"b\\\\c\"", new TsonDataEmitter().quotedString("a\"b\\c").toString());
    }

    @Test
    void quotedStringEscapesControlCharacters() {
        assertEquals("\"a\\nb\\tc\"", new TsonDataEmitter().quotedString("a\nb\tc").toString());
    }

    @Test
    void quotedStringEscapesOtherC0ControlAsUnicodeEscape() {
        assertEquals("\"a\\u0001b\"", new TsonDataEmitter().quotedString("a" + '\u0001' + "b").toString());
    }

    @Test
    void quotedStringLeavesNonAsciiLiteral() {
        assertEquals("\"héllo\"", new TsonDataEmitter().quotedString("héllo").toString());
    }

    @Test
    void absentIsTheOnlyNoValueToken() {
        assertEquals("_", new TsonDataEmitter().absentValue().toString());
        // `null` has no emitter of its own: it is an ordinary unquoted string token.
        assertEquals("null", new TsonDataEmitter().unquotedToken("null").toString());
    }

    @Test
    void booleanTokens() {
        assertEquals("true", new TsonDataEmitter().booleanValue(true).toString());
        assertEquals("false", new TsonDataEmitter().booleanValue(false).toString());
    }

    // ── Header directives (§2.2, §3.3) ──────────────────────────────────

    @Test
    void directivesAreWrittenOnTheirOwnLinesInHeaderOrder() {
        String tson = new TsonDataEmitter()
                .documentId("https://example.test/doc-1.tn")
                .schemaRef("https://example.test/point.tn")
                .beginRecord().field("x").unquotedToken("1").endRecord()
                .toString();

        assertEquals("""
                !!id:"https://example.test/doc-1.tn"
                !!schema:"https://example.test/point.tn"
                { x: 1 }""", tson);
    }

    /**
     * The terminator is load-bearing, not formatting: §2.2.1 bounds the content-hash input at the id line's
     * own terminator, so an {@code !!id} sharing a line with what follows has no defined hash.
     */
    @Test
    void theIdDirectiveEndsItsLine() {
        assertTrue(new TsonDataEmitter().documentId("https://example.test/doc-1.tn").toString().endsWith("\n"));
    }

    /** A directive argument MUST be a URI (§3.3), so a caller cannot emit a document that will not read back. */
    @Test
    void aDirectiveArgumentThatIsNotAUriIsRefused() {
        TsonWriteException thrown = assertThrows(TsonWriteException.class,
                () -> new TsonDataEmitter().schemaRef("not a uri"));

        assertTrue(thrown.getMessage().contains("is not a valid URI"), thrown.getMessage());
    }

    // ── At most one type-ref per value (§3.2) ───────────────────────────

    @Test
    void asecondTypeRefOnOneValueIsRefused() {
        TsonWriteException thrown = assertThrows(TsonWriteException.class,
                () -> new TsonDataEmitter().typeRef("point").typeRef("uuid"));

        assertTrue(thrown.getMessage().contains("two type annotations on one value"), thrown.getMessage());
    }

    /** The count is per value, not per document: a nested value's own type-ref is a different value's. */
    @Test
    void aNestedValueMayCarryItsOwnTypeRef() {
        String tson = new TsonDataEmitter()
                .typeRef("point")
                .beginRecord()
                .field("x").typeRef("int32").unquotedToken("1")
                .field("y").typeRef("int32").unquotedToken("2")
                .endRecord()
                .toString();

        assertEquals("!point { x: !int32 1 y: !int32 2 }", tson);
    }
}
