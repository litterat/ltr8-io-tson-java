package io.ltr8.tson.parser.resolver.vocab;

import io.ltr8.tson.parser.ast.TokenForm;
import io.ltr8.tson.parser.ast.TokenValue;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TextParserTest {

    private static TokenValue token(String text) {
        return new TokenValue(text, TokenForm.UNQUOTED);
    }

    @Test
    void unconstrainedAcceptsAnyText() {
        assertEquals("hello", TextParser.UNCONSTRAINED.read(token("hello")));
        assertEquals("", TextParser.UNCONSTRAINED.read(token("")));
    }

    @Test
    void exactLengthRejectsWrongLength() {
        TextParser type = new TextParser(Optional.empty(), Optional.empty(), Optional.of(5), Optional.empty());
        assertEquals("hello", type.read(token("hello")));
        assertThrows(AtomValidationException.class, () -> type.read(token("hi")));
    }

    @Test
    void minLengthRejectsShorterText() {
        TextParser type = new TextParser(Optional.of(3), Optional.empty(), Optional.empty(), Optional.empty());
        assertEquals("abc", type.read(token("abc")));
        assertThrows(AtomValidationException.class, () -> type.read(token("ab")));
    }

    @Test
    void maxLengthRejectsLongerText() {
        TextParser type = new TextParser(Optional.empty(), Optional.of(3), Optional.empty(), Optional.empty());
        assertEquals("abc", type.read(token("abc")));
        assertThrows(AtomValidationException.class, () -> type.read(token("abcd")));
    }

    @Test
    void patternRejectsNonMatchingText() {
        TextParser type = new TextParser(Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("[a-z]+"));
        assertEquals("abc", type.read(token("abc")));
        assertThrows(AtomValidationException.class, () -> type.read(token("ABC")));
    }

    @Test
    void writeRoundTripsThroughRead() {
        assertEquals("hello", TextParser.UNCONSTRAINED.write(TextParser.UNCONSTRAINED.read(token("hello"))));
    }
}
