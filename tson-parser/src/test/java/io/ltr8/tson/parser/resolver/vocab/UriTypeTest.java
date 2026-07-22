package io.ltr8.tson.parser.resolver.vocab;

import io.ltr8.tson.parser.ast.TokenForm;
import io.ltr8.tson.parser.ast.TokenValue;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UriTypeTest {

    private static TokenValue token(String text) {
        return new TokenValue(text, TokenForm.UNQUOTED);
    }

    @Test
    void acceptsAbsoluteUri() {
        assertEquals(URI.create("https://example.com/a/b?x=1#frag"),
                UriType.UNCONSTRAINED.read(token("https://example.com/a/b?x=1#frag")));
    }

    @Test
    void acceptsRelativeReference() {
        assertEquals(URI.create("foo/bar?x=1"), UriType.UNCONSTRAINED.read(token("foo/bar?x=1")));
    }

    @Test
    void acceptsUrnScheme() {
        assertEquals(URI.create("urn:isbn:0451450523"), UriType.UNCONSTRAINED.read(token("urn:isbn:0451450523")));
    }

    @Test
    void malformedUriIsAParseError() {
        // An unescaped space is not valid anywhere in a URI.
        assertThrows(AtomParseException.class, () -> UriType.UNCONSTRAINED.read(token("http://example.com/a b")));
    }

    @Test
    void minLengthRejectsShorterUri() {
        UriType type = new UriType(Optional.of(20), Optional.empty(), Optional.empty(), Optional.empty());
        assertEquals(URI.create("https://example.com/"), type.read(token("https://example.com/")));
        assertThrows(AtomValidationException.class, () -> type.read(token("urn:x")));
    }

    @Test
    void maxLengthRejectsLongerUri() {
        UriType type = new UriType(Optional.empty(), Optional.of(6), Optional.empty(), Optional.empty());
        assertEquals(URI.create("urn:x"), type.read(token("urn:x")));
        assertThrows(AtomValidationException.class, () -> type.read(token("https://example.com/")));
    }

    @Test
    void patternRejectsNonMatchingUri() {
        UriType type = new UriType(Optional.empty(), Optional.empty(), Optional.of(Pattern.compile("https://.*")), Optional.empty());
        assertEquals(URI.create("https://example.com/"), type.read(token("https://example.com/")));
        assertThrows(AtomValidationException.class, () -> type.read(token("http://example.com/")));
    }

    @Test
    void schemeConstraintRejectsMismatchedScheme() {
        UriType type = new UriType(Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("https"));
        assertEquals(URI.create("https://example.com/"), type.read(token("https://example.com/")));
        assertThrows(AtomValidationException.class, () -> type.read(token("http://example.com/")));
    }

    @Test
    void schemeConstraintRejectsSchemelessReference() {
        UriType type = new UriType(Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("https"));
        assertThrows(AtomValidationException.class, () -> type.read(token("foo/bar")));
    }
}
