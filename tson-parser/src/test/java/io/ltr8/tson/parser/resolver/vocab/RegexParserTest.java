package io.ltr8.tson.parser.resolver.vocab;

import io.ltr8.tson.parser.ast.TokenForm;
import io.ltr8.tson.parser.ast.TokenValue;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.regex.Pattern;

import java.util.regex.Matcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegexParserTest {

    private static TokenValue token(String text) {
        return new TokenValue(text, TokenForm.UNQUOTED);
    }

    @Test
    void acceptsAValidJavaRegex() {
        Pattern p = RegexParser.UNCONSTRAINED.read(token("[a-z]+"));
        assertTrue(p.matcher("abc").matches());
    }

    @Test
    void rejectsSyntacticallyInvalidRegex() {
        // Unbalanced group -- invalid under java.util.regex, which is this atom's accepted contract
        // (see RegexParser's own Javadoc on why that's not the same as RFC 9485 I-Regexp).
        assertThrows(AtomParseException.class, () -> RegexParser.UNCONSTRAINED.read(token("[a-z")));
    }

    @Test
    void acceptsJavaRegexConstructsThatAreNotValidIRegexpEitherWay() {
        // A named group is valid java.util.regex syntax but not part of RFC 9485 -- accepted here
        // regardless, since this atom's contract is "compiles under java.util.regex", not "is a
        // conformant I-Regexp pattern".
        Pattern p = RegexParser.UNCONSTRAINED.read(token("(?<year>[0-9]{4})"));
        Matcher m = p.matcher("2026");
        assertTrue(m.matches());
        assertEquals("2026", m.group("year"));
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
