package io.ltr8.tson.parser.resolver.vocab;

import io.ltr8.tson.parser.ast.TokenForm;
import io.ltr8.tson.parser.ast.TokenValue;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TextTypeTest {

    private static TokenValue token(String text) {
        return new TokenValue(text, TokenForm.UNQUOTED);
    }

    @Test
    void unconstrainedAcceptsAnyText() {
        assertEquals("hello", TextType.UNCONSTRAINED.read(token("hello")));
        assertEquals("", TextType.UNCONSTRAINED.read(token("")));
    }

    @Test
    void exactLengthRejectsWrongLength() {
        TextType type = new TextType(Optional.empty(), Optional.empty(), Optional.of(5), Optional.empty());
        assertEquals("hello", type.read(token("hello")));
        assertThrows(AtomValidationException.class, () -> type.read(token("hi")));
    }

    @Test
    void minLengthRejectsShorterText() {
        TextType type = new TextType(Optional.of(3), Optional.empty(), Optional.empty(), Optional.empty());
        assertEquals("abc", type.read(token("abc")));
        assertThrows(AtomValidationException.class, () -> type.read(token("ab")));
    }

    @Test
    void maxLengthRejectsLongerText() {
        TextType type = new TextType(Optional.empty(), Optional.of(3), Optional.empty(), Optional.empty());
        assertEquals("abc", type.read(token("abc")));
        assertThrows(AtomValidationException.class, () -> type.read(token("abcd")));
    }

    @Test
    void patternRejectsNonMatchingText() {
        TextType type = new TextType(Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(Pattern.compile("[a-z]+")));
        assertEquals("abc", type.read(token("abc")));
        assertThrows(AtomValidationException.class, () -> type.read(token("ABC")));
    }

    @Test
    void writeRoundTripsThroughRead() {
        assertEquals("hello", TextType.UNCONSTRAINED.write(TextType.UNCONSTRAINED.read(token("hello"))));
    }
}
