package io.ltr8.tson.compiler.atom;

import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import org.junit.jupiter.api.Test;

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

    /**
     * The unconstrained parser's own cited document. That this passes says nothing about whether a
     * {@code regex_type} body <em>resolved from a schema</em> carries the citation -- the constant is
     * hand-written here, so it is right by construction; {@code DefinitionResolverTest} covers the
     * binding.
     */
    @Test
    void citesRfc9485NotUriTypesRfc3986() {
        // regex_type => ~text_type & atom_specification & { spec: = "https://.../rfc9485" } --
        // the same atom_specification mixin uri_type composes, but a different cited RFC.
        assertEquals("https://www.rfc-editor.org/rfc/rfc9485", RegexParser.UNCONSTRAINED.constraints().spec());
    }
}
