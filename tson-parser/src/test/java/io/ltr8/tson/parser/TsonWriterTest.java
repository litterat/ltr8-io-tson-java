package io.ltr8.tson.parser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TsonWriterTest {

    @Test
    void emptyRecord() {
        assertEquals("{}", new TsonWriter().beginRecord().endRecord().toString());
    }

    @Test
    void emptyArray() {
        assertEquals("[]", new TsonWriter().beginArray().endArray().toString());
    }

    @Test
    void simpleRecord() {
        String tson = new TsonWriter()
                .beginRecord()
                .field("x").unquotedToken("1")
                .field("y").unquotedToken("2")
                .endRecord()
                .toString();
        assertEquals("{ x: 1 y: 2 }", tson);
    }

    @Test
    void simpleArray() {
        String tson = new TsonWriter()
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
        String tson = new TsonWriter()
                .beginMap()
                .beforeMapEntry().unquotedToken("WELCOME10").mapArrow().quotedString("10%")
                .endMap()
                .toString();
        assertEquals("{ WELCOME10 => \"10%\" }", tson);
    }

    @Test
    void nestedRecordInArray() {
        String tson = new TsonWriter()
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
        String tson = new TsonWriter().typeRef("uuid").quotedString("9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09").toString();
        assertEquals("!uuid \"9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09\"", tson);
    }

    @Test
    void quotedStringEscapesQuoteAndBackslash() {
        assertEquals("\"a\\\"b\\\\c\"", new TsonWriter().quotedString("a\"b\\c").toString());
    }

    @Test
    void quotedStringEscapesControlCharacters() {
        assertEquals("\"a\\nb\\tc\"", new TsonWriter().quotedString("a\nb\tc").toString());
    }

    @Test
    void quotedStringEscapesOtherC0ControlAsUnicodeEscape() {
        assertEquals("\"a\\u0001b\"", new TsonWriter().quotedString("a" + '\u0001' + "b").toString());
    }

    @Test
    void quotedStringLeavesNonAsciiLiteral() {
        assertEquals("\"héllo\"", new TsonWriter().quotedString("héllo").toString());
    }

    @Test
    void nullAndAbsentAreDistinctTokens() {
        assertEquals("null", new TsonWriter().nullValue().toString());
        assertEquals("_", new TsonWriter().absentValue().toString());
    }

    @Test
    void booleanTokens() {
        assertEquals("true", new TsonWriter().booleanValue(true).toString());
        assertEquals("false", new TsonWriter().booleanValue(false).toString());
    }
}
