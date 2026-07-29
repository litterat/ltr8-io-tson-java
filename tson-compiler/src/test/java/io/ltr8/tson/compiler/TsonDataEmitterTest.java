package io.ltr8.tson.compiler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void nullAndAbsentAreDistinctTokens() {
        assertEquals("null", new TsonDataEmitter().nullValue().toString());
        assertEquals("_", new TsonDataEmitter().absentValue().toString());
    }

    @Test
    void booleanTokens() {
        assertEquals("true", new TsonDataEmitter().booleanValue(true).toString());
        assertEquals("false", new TsonDataEmitter().booleanValue(false).toString());
    }
}
