package io.ltr8.tson.parser.resolver.vocab;

import io.ltr8.tson.parser.ast.TokenForm;
import io.ltr8.tson.parser.ast.TokenValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UnitParserTest {

    private static TokenValue token(String text) {
        return new TokenValue(text, TokenForm.UNQUOTED);
    }

    @Test
    void readAcceptsAnyTokenUnconstrained() {
        assertEquals("anything", UnitParser.INSTANCE.read(token("anything")));
        assertEquals("42", UnitParser.INSTANCE.read(token("42")));
        assertEquals("", UnitParser.INSTANCE.read(token("")));
    }

    @Test
    void writeReturnsTheTextUnchanged() {
        assertEquals("anything", UnitParser.INSTANCE.write("anything"));
    }
}
