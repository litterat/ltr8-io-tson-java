package io.ltr8.tson.atom.parser;

import io.ltr8.tson.atom.AtomParseException;
import io.ltr8.tson.atom.AtomValidationException;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UriParserTest {

    private static String token(String text) {
        return text;
    }

    @Test
    void acceptsAbsoluteUri() {
        assertEquals(URI.create("https://example.com/a/b?x=1#frag"),
                UriParser.UNCONSTRAINED.read(token("https://example.com/a/b?x=1#frag")));
    }

    @Test
    void uriReferenceAcceptsARelativeReference() {
        assertEquals(URI.create("foo/bar?x=1"), UriParser.REFERENCE.read(token("foo/bar?x=1")));
    }

    @Test
    void uriReferenceAcceptsAUri() {
        assertEquals(URI.create("urn:isbn:0451450523"), UriParser.REFERENCE.read(token("urn:isbn:0451450523")));
    }

    /** RFC 3986 §3: a URI has a scheme, so a relative reference is a valid token outside the value space. */
    @Test
    void uriRefusesARelativeReference() {
        AtomValidationException refused = assertThrows(AtomValidationException.class,
                () -> UriParser.UNCONSTRAINED.read(token("foo/bar?x=1")));
        assertEquals("an RFC 3986 URI", refused.expected());
    }

    @Test
    void acceptsUrnScheme() {
        assertEquals(URI.create("urn:isbn:0451450523"), UriParser.UNCONSTRAINED.read(token("urn:isbn:0451450523")));
    }

    /** RFC 3986 §2: a URI is US-ASCII; the same text beyond it is an IRI, and {@code !uri} refuses it. */
    @Test
    void refusesACharacterBeyondUsAscii() {
        AtomParseException refused = assertThrows(AtomParseException.class,
                () -> UriParser.REFERENCE.read(token("https://example.com/caf\u00E9")));
        assertEquals("a URI", refused.expected());
        assertEquals(URI.create("https://example.com/caf%C3%A9"),
                UriParser.REFERENCE.read(token("https://example.com/caf%C3%A9")));
    }

    @Test
    void malformedUriIsAParseError() {
        // An unescaped space is not valid anywhere in a URI.
        assertThrows(AtomParseException.class, () -> UriParser.UNCONSTRAINED.read(token("http://example.com/a b")));
    }

    @Test
    void minLengthRejectsShorterUri() {
        UriParser type = new UriParser(Optional.of(20), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), true, true);
        assertEquals(URI.create("https://example.com/"), type.read(token("https://example.com/")));
        assertThrows(AtomValidationException.class, () -> type.read(token("urn:x")));
    }

    @Test
    void maxLengthRejectsLongerUri() {
        UriParser type = new UriParser(Optional.empty(), Optional.of(6), Optional.empty(),
                Optional.empty(), Optional.empty(), true, true);
        assertEquals(URI.create("urn:x"), type.read(token("urn:x")));
        assertThrows(AtomValidationException.class, () -> type.read(token("https://example.com/")));
    }

    @Test
    void patternRejectsNonMatchingUri() {
        UriParser type = new UriParser(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of("https://.*"), Optional.empty(), true, true);
        assertEquals(URI.create("https://example.com/"), type.read(token("https://example.com/")));
        assertThrows(AtomValidationException.class, () -> type.read(token("http://example.com/")));
    }

    @Test
    void schemeConstraintRejectsMismatchedScheme() {
        UriParser type = new UriParser(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(List.of("https")), true, true);
        assertEquals(URI.create("https://example.com/"), type.read(token("https://example.com/")));
        assertThrows(AtomValidationException.class, () -> type.read(token("http://example.com/")));
    }

    @Test
    void schemeConstraintRejectsSchemelessReference() {
        UriParser type = new UriParser(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(List.of("https")), true, true);
        assertThrows(AtomValidationException.class, () -> type.read(token("foo/bar")));
    }

    /** RFC 3986 §3.1: a scheme is compared case-insensitively, and a set admits any of its members. */
    @Test
    void schemesAdmitAnyMemberWhateverItsCase() {
        UriParser type = new UriParser(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(List.of("http", "https")), false, true);
        assertEquals(URI.create("HTTPS://example.com/"), type.read(token("HTTPS://example.com/")));
        assertEquals(URI.create("http://example.com/"), type.read(token("http://example.com/")));
        AtomValidationException ftp = assertThrows(AtomValidationException.class,
                () -> type.read(token("ftp://example.com/")));
        assertEquals("scheme one of (http, https)", ftp.expected());
    }

    /** {@code allow_fragment} withdrawn refuses a fragment, an empty one included. */
    @Test
    void allowFragmentWithdrawnRefusesAFragment() {
        UriParser type = new UriParser(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), false, false);
        assertEquals(URI.create("https://example.com/a?x=1"), type.read(token("https://example.com/a?x=1")));
        AtomValidationException fragment = assertThrows(AtomValidationException.class,
                () -> type.read(token("https://example.com/a#top")));
        assertEquals("no fragment", fragment.expected());
        assertThrows(AtomValidationException.class, () -> type.read(token("https://example.com/a#")));
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
                Optional.empty(), Optional.empty(), true, true);

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
        // uri_type => text_type & atom_specification & { spec: = "https://.../rfc3986" ... } --
        // the same atom_specification mixin regex_type composes, but a different cited RFC.
        assertEquals("https://www.rfc-editor.org/rfc/rfc3986", UriParser.UNCONSTRAINED.constraints().spec());
    }
}
