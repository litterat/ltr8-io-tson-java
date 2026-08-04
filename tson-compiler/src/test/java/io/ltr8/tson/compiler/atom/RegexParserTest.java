package io.ltr8.tson.compiler.atom;

import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RegexParserTest {

    private static TokenValue token(String text) {
        return new TokenValue(text, TokenForm.UNQUOTED);
    }

    @Test
    void acceptsAValidIRegexpAndReturnsItsText() {
        assertEquals("[a-z]+", RegexParser.UNCONSTRAINED.read(token("[a-z]+")));
    }

    @Test
    void rejectsSyntacticallyInvalidRegex() {
        // Unbalanced character class -- invalid I-Regexp (and everything else).
        assertThrows(AtomParseException.class, () -> RegexParser.UNCONSTRAINED.read(token("[a-z")));
    }

    @Test
    void rejectsConstructsOutsideTheIRegexpSubset() {
        // A named capture group and a \d escape are valid java.util.regex but not I-Regexp -- validation
        // goes through tson-regex (RFC 9485), so this atom rejects them rather than inheriting the JVM's
        // laxer grammar (the whole point of the regex_type spec pin; see RegexParser's Javadoc).
        assertThrows(AtomParseException.class, () -> RegexParser.UNCONSTRAINED.read(token("(?<year>[0-9]{4})")));
        assertThrows(AtomParseException.class, () -> RegexParser.UNCONSTRAINED.read(token("\\d+")));
    }

    @Test
    void writeRoundTripsThroughRead() {
        String written = RegexParser.UNCONSTRAINED.write(RegexParser.UNCONSTRAINED.read(token("[a-z]+")));
        assertEquals("[a-z]+", written);
    }

    @Test
    void citesRfc9485ViaTheComposedAtomSpecificationNotRfc3986() {
        // regex_type => ~text_type & atom_specification & { spec: = "https://.../rfc9485" } --
        // the same atom_specification mixin UriParser composes, but a different cited RFC.
        assertEquals(URI.create("https://www.rfc-editor.org/rfc/rfc9485"),
                RegexParser.UNCONSTRAINED.constraints().specification().spec());
    }
}
