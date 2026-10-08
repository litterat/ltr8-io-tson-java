package io.ltr8.tson.atom.parser;

import io.ltr8.tson.atom.AtomParseException;
import io.ltr8.tson.atom.AtomValidationException;
import org.junit.jupiter.api.Test;
import io.ltr8.net.Iri;
import io.ltr8.tson.atom.AtomType;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UriParserTest {

    private static String token(String text) {
        return text;
    }

    private static Iri uri(String text) {
        return Iri.parse(text, Iri.Grammar.URI);
    }

    /**
     * RFC 3986's grammar, not {@code java.net.URI}'s RFC 2396 one: an empty host, an empty path and an IPvFuture
     * literal are URIs, and a port that is not digits is not.
     */
    @Test
    void theGrammarIsRfc3986s() {
        for (String valid : List.of("https://", "https://?q=1", "a:", "http://[v7.abc]/")) {
            assertEquals(uri(valid), UriParser.UNCONSTRAINED.read(token(valid)), valid);
        }
        assertThrows(AtomParseException.class, () -> UriParser.UNCONSTRAINED.read(token("http://a:b/")));
    }

    /**
     * {@code java.net.URI} is a binding target and never the judge: a value it can hold binds, and one it cannot is
     * refused at that component as a binding failure, its validity unchanged.
     */
    @Test
    void aJavaUriComponentHoldsWhatRfc2396Can() {
        AtomType<?> asUri = UriParser.UNCONSTRAINED.boundTo(URI.class).orElseThrow();
        assertEquals(URI.create("https://example.com/a"), asUri.read("https://example.com/a"));
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> asUri.read("https://"));
        assertTrue(refused.getMessage().contains("java.net.URI cannot hold it"), refused.getMessage());
        assertEquals(asUri, asUri.boundTo(URI.class).orElseThrow());
        assertEquals("https://", UriParser.UNCONSTRAINED.boundTo(String.class).orElseThrow().read("https://"));
    }

    @Test
    void acceptsAbsoluteUri() {
        assertEquals(uri("https://example.com/a/b?x=1#frag"),
                UriParser.UNCONSTRAINED.read(token("https://example.com/a/b?x=1#frag")));
    }

    @Test
    void uriReferenceAcceptsARelativeReference() {
        assertEquals(uri("foo/bar?x=1"), UriParser.REFERENCE.read(token("foo/bar?x=1")));
    }

    @Test
    void uriReferenceAcceptsAUri() {
        assertEquals(uri("urn:isbn:0451450523"), UriParser.REFERENCE.read(token("urn:isbn:0451450523")));
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
        assertEquals(uri("urn:isbn:0451450523"), UriParser.UNCONSTRAINED.read(token("urn:isbn:0451450523")));
    }

    /** RFC 3986 §2: a URI is US-ASCII; the same text beyond it is an IRI, and {@code !uri} refuses it. */
    @Test
    void refusesACharacterBeyondUsAscii() {
        AtomParseException refused = assertThrows(AtomParseException.class,
                () -> UriParser.REFERENCE.read(token("https://example.com/caf\u00E9")));
        assertEquals("a URI", refused.expected());
        assertEquals(uri("https://example.com/caf%C3%A9"),
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
        assertEquals(uri("https://example.com/"), type.read(token("https://example.com/")));
        assertThrows(AtomValidationException.class, () -> type.read(token("urn:x")));
    }

    @Test
    void maxLengthRejectsLongerUri() {
        UriParser type = new UriParser(Optional.empty(), Optional.of(6), Optional.empty(),
                Optional.empty(), Optional.empty(), true, true);
        assertEquals(uri("urn:x"), type.read(token("urn:x")));
        assertThrows(AtomValidationException.class, () -> type.read(token("https://example.com/")));
    }

    @Test
    void patternRejectsNonMatchingUri() {
        UriParser type = new UriParser(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of("https://.*"), Optional.empty(), true, true);
        assertEquals(uri("https://example.com/"), type.read(token("https://example.com/")));
        assertThrows(AtomValidationException.class, () -> type.read(token("http://example.com/")));
    }

    @Test
    void schemeConstraintRejectsMismatchedScheme() {
        UriParser type = new UriParser(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(List.of("https")), true, true);
        assertEquals(uri("https://example.com/"), type.read(token("https://example.com/")));
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
        assertEquals(uri("HTTPS://example.com/"), type.read(token("HTTPS://example.com/")));
        assertEquals(uri("http://example.com/"), type.read(token("http://example.com/")));
        AtomValidationException ftp = assertThrows(AtomValidationException.class,
                () -> type.read(token("ftp://example.com/")));
        assertEquals("scheme one of (http, https)", ftp.expected());
    }

    /** {@code allow_fragment} withdrawn refuses a fragment, an empty one included. */
    @Test
    void allowFragmentWithdrawnRefusesAFragment() {
        UriParser type = new UriParser(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), false, false);
        assertEquals(uri("https://example.com/a?x=1"), type.read(token("https://example.com/a?x=1")));
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

        assertEquals(uri("https://example.com"), type.read(token("https://example.com")));
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
