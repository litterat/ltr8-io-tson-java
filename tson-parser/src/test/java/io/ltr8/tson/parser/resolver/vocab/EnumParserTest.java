package io.ltr8.tson.parser.resolver.vocab;

import io.ltr8.tson.parser.ast.TokenForm;
import io.ltr8.tson.parser.ast.TokenValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EnumParserTest {

    private static TokenValue token(String text, TokenForm form) {
        return new TokenValue(text, form);
    }

    @Test
    void readAcceptsAKnownMember() {
        EnumParser type = new EnumParser(List.of("true", "false"));
        assertEquals("true", type.read(token("true", TokenForm.UNQUOTED)));
        assertEquals("false", type.read(token("false", TokenForm.UNQUOTED)));
    }

    @Test
    void readRejectsAnUnknownMember() {
        EnumParser type = new EnumParser(List.of("true", "false"));
        assertThrows(AtomValidationException.class, () -> type.read(token("maybe", TokenForm.UNQUOTED)));
    }

    @Test
    void matchesByTextRegardlessOfTokenForm() {
        // The same form-agnostic behavior MetaKernelParser's own hand-written enum converter uses --
        // a quoted "true" is still the member "true".
        EnumParser type = new EnumParser(List.of("true", "false"));
        assertEquals("true", type.read(token("true", TokenForm.SINGLE_LINE_QUOTED)));
    }

    @Test
    void booleanLiteralLookingMembersDoNotCollideWithBaseTypeIdentification() {
        // The exact boolean => !enum [true false] case -- read() never routes through
        // BaseTypeResolver, so "true"/"false" are matched as plain enum member text, not
        // misidentified as actual TSON booleans.
        EnumParser type = new EnumParser(List.of("true", "false"));
        assertEquals("false", type.read(token("false", TokenForm.UNQUOTED)));
    }

    @Test
    void writeReturnsTheMemberTextUnchanged() {
        EnumParser type = new EnumParser(List.of("true", "false"));
        assertEquals("true", type.write("true"));
    }
}
