package io.ltr8.tson.parser.atom;

import io.ltr8.tson.parser.ast.TokenForm;
import io.ltr8.tson.parser.ast.TokenValue;
import io.ltr8.tson.parser.atom.TokenParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TokenParserTest {

    private static TokenValue token(String text) {
        return new TokenValue(text, TokenForm.UNQUOTED);
    }

    @Test
    void readAcceptsAnyTokenUnconstrained() {
        assertEquals("anything", TokenParser.INSTANCE.read(token("anything")));
        assertEquals("42", TokenParser.INSTANCE.read(token("42")));
        assertEquals("", TokenParser.INSTANCE.read(token("")));
    }

    @Test
    void writeReturnsTheTextUnchanged() {
        assertEquals("anything", TokenParser.INSTANCE.write("anything"));
    }
}
