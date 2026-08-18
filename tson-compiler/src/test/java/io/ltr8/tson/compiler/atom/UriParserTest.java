package io.ltr8.tson.compiler.atom;

import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UriParserTest {

    private static TokenValue token(String text) {
        return new TokenValue(text, TokenForm.UNQUOTED);
    }

    @Test
    void acceptsAbsoluteUri() {
        assertEquals(URI.create("https://example.com/a/b?x=1#frag"),
                UriParser.UNCONSTRAINED.read(token("https://example.com/a/b?x=1#frag")));
    }

    @Test
    void acceptsRelativeReference() {
        assertEquals(URI.create("foo/bar?x=1"), UriParser.UNCONSTRAINED.read(token("foo/bar?x=1")));
    }

    @Test
    void acceptsUrnScheme() {
        assertEquals(URI.create("urn:isbn:0451450523"), UriParser.UNCONSTRAINED.read(token("urn:isbn:0451450523")));
    }

    @Test
    void malformedUriIsAParseError() {
        // An unescaped space is not valid anywhere in a URI.
        assertThrows(AtomParseException.class, () -> UriParser.UNCONSTRAINED.read(token("http://example.com/a b")));
    }

    @Test
    void minLengthRejectsShorterUri() {
        UriParser type = new UriParser(Optional.of(20), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        assertEquals(URI.create("https://example.com/"), type.read(token("https://example.com/")));
        assertThrows(AtomValidationException.class, () -> type.read(token("urn:x")));
    }

    @Test
    void maxLengthRejectsLongerUri() {
        UriParser type = new UriParser(Optional.empty(), Optional.of(6), Optional.empty(), Optional.empty(), Optional.empty());
        assertEquals(URI.create("urn:x"), type.read(token("urn:x")));
        assertThrows(AtomValidationException.class, () -> type.read(token("https://example.com/")));
    }

    @Test
    void patternRejectsNonMatchingUri() {
        UriParser type = new UriParser(Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("https://.*"), Optional.empty());
        assertEquals(URI.create("https://example.com/"), type.read(token("https://example.com/")));
        assertThrows(AtomValidationException.class, () -> type.read(token("http://example.com/")));
    }

    @Test
    void schemeConstraintRejectsMismatchedScheme() {
        UriParser type = new UriParser(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("https"));
        assertEquals(URI.create("https://example.com/"), type.read(token("https://example.com/")));
        assertThrows(AtomValidationException.class, () -> type.read(token("http://example.com/")));
    }

    @Test
    void schemeConstraintRejectsSchemelessReference() {
        UriParser type = new UriParser(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("https"));
        assertThrows(AtomValidationException.class, () -> type.read(token("foo/bar")));
    }

    @Test
    void writeRoundTripsThroughRead() {
        String text = "https://example.com/a/b?x=1#frag";
        assertEquals(text, UriParser.UNCONSTRAINED.write(UriParser.UNCONSTRAINED.read(token(text))));
    }

    @Test
    void exactLengthConstraintRejectsAnythingElse() {
        // The facet text_type composes into uri_type that had no component here at all to bind into,
        // so it was silently unenforceable however a schema declared it.
        UriParser type = new UriParser(Optional.empty(), Optional.empty(), Optional.of(19),
                Optional.empty(), Optional.empty());

        assertEquals(URI.create("https://example.com"), type.read(token("https://example.com")));
        assertThrows(AtomValidationException.class, () -> type.read(token("https://example.com/a")));
    }

    /**
     * The unconstrained parser's own cited document. That this passes says nothing about whether a
     * {@code uri_type} body <em>resolved from a schema</em> carries the citation -- the constant is
     * hand-written here, so it is right by construction; {@code DefinitionResolverTest} covers the
     * binding.
     */
    @Test
    void citesRfc3986NotRegexTypesRfc9485() {
        // uri_type => ~text_type & atom_specification & { spec: = "https://.../rfc3986" ... } --
        // the same atom_specification mixin regex_type composes, but a different cited RFC.
        assertEquals("https://www.rfc-editor.org/rfc/rfc3986", UriParser.UNCONSTRAINED.constraints().spec());
    }
}
