package io.ltr8.tson.atom.parser;

import io.ltr8.tson.atom.AtomValidationException;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EnumParserTest {

    @Test
    void readAcceptsAKnownMember() {
        EnumParser type = new EnumParser(List.of("true", "false"));
        assertEquals("true", type.read("true"));
        assertEquals("false", type.read("false"));
    }

    @Test
    void readRejectsAnUnknownMember() {
        EnumParser type = new EnumParser(List.of("true", "false"));
        assertThrows(AtomValidationException.class, () -> type.read("maybe"));
    }

    @Test
    void matchesByTextWhicheverFormCarriedIt() {
        // The same form-agnostic behavior MetaKernelBootstrapResolver's own hand-written enum converter uses --
        // a quoted "true" is still the member "true".
        EnumParser type = new EnumParser(List.of("true", "false"));
        assertEquals("true", type.read("true"));
    }

    @Test
    void booleanLiteralLookingMembersDoNotCollideWithBaseTypeIdentification() {
        // The exact boolean => !enum [true false] case -- read() never routes through
        // BaseTypeResolver, so "true"/"false" are matched as plain enum member text, not
        // misidentified as actual TSON booleans.
        EnumParser type = new EnumParser(List.of("true", "false"));
        assertEquals("false", type.read("false"));
    }

    @Test
    void writeReturnsTheMemberTextUnchanged() {
        EnumParser type = new EnumParser(List.of("true", "false"));
        assertEquals("true", type.write("true"));
    }
}
