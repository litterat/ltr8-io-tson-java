package io.ltr8.tson.parser.atom;

import io.ltr8.tson.parser.ast.TokenForm;
import io.ltr8.tson.parser.ast.TokenValue;
import io.ltr8.tson.parser.atom.ValueParser;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValueParserTest {

    private static TokenValue unquoted(String text) {
        return new TokenValue(text, TokenForm.UNQUOTED);
    }

    private static TokenValue quoted(String text) {
        return new TokenValue(text, TokenForm.SINGLE_LINE_QUOTED);
    }

    @Test
    void readsNullAsJavaNull() {
        assertNull(ValueParser.INSTANCE.read(unquoted("null")));
    }

    @Test
    void readsBooleansAsJavaBooleans() {
        assertEquals(Boolean.TRUE, ValueParser.INSTANCE.read(unquoted("true")));
        assertEquals(Boolean.FALSE, ValueParser.INSTANCE.read(unquoted("false")));
    }

    @Test
    void readsIntegersAsBigInteger() {
        assertEquals(BigInteger.valueOf(255), ValueParser.INSTANCE.read(unquoted("255")));
        assertEquals(BigInteger.valueOf(255), ValueParser.INSTANCE.read(unquoted("0xFF")));
    }

    @Test
    void readsFloatsAsBigDecimal() {
        assertEquals(new BigDecimal("0.5"), ValueParser.INSTANCE.read(unquoted(".5")));
    }

    @Test
    void readsSpecialNumericFormsAsDouble() {
        assertTrue(((Double) ValueParser.INSTANCE.read(unquoted(".nan"))).isNaN());
        assertEquals(Double.POSITIVE_INFINITY, ValueParser.INSTANCE.read(unquoted(".inf")));
        assertEquals(Double.NEGATIVE_INFINITY, ValueParser.INSTANCE.read(unquoted("-.inf")));
    }

    @Test
    void readsUnquotedNonMatchesAsString() {
        assertEquals("hello", ValueParser.INSTANCE.read(unquoted("hello")));
    }

    @Test
    void quotedTokensAlwaysReadAsStringRegardlessOfContent() {
        assertEquals("42", ValueParser.INSTANCE.read(quoted("42")));
        assertEquals("true", ValueParser.INSTANCE.read(quoted("true")));
        assertEquals("null", ValueParser.INSTANCE.read(quoted("null")));
    }

    @Test
    void writeIsTheInverseForEveryVariant() {
        assertEquals("null", ValueParser.INSTANCE.write(null));
        assertEquals("true", ValueParser.INSTANCE.write(true));
        assertEquals("255", ValueParser.INSTANCE.write(BigInteger.valueOf(255)));
        assertEquals("0.5", ValueParser.INSTANCE.write(new BigDecimal("0.5")));
        assertEquals(".nan", ValueParser.INSTANCE.write(Double.NaN));
        assertEquals(".inf", ValueParser.INSTANCE.write(Double.POSITIVE_INFINITY));
        assertEquals("-.inf", ValueParser.INSTANCE.write(Double.NEGATIVE_INFINITY));
        assertEquals("hello", ValueParser.INSTANCE.write("hello"));
    }
}
