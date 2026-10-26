package io.ltr8.tson.base;

import io.ltr8.net.Host;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@link Identity}: [TSON-DATA] §2.2.1's canonical identity as a value. */
class IdentityTest {

    private static final String PIN = "0123456789abcdef".repeat(4);

    @Test
    void equalityIsOverTheCanonicalIdentity() {
        Set<Identity> one = Set.of(Identity.parse("tson.io/2026/38/m/core.tn"));
        for (String spelling : new String[] {
                "https://tson.io/2026/38/m/core.tn",
                "http://tson.io/2026/38/m/core.tn?sha256=" + PIN,
                "HTTPS://Tson.IO/2026/38/m/core.tn"}) {
            Identity value = Identity.parse(spelling);
            assertEquals(one, Set.of(value), spelling);
            assertEquals(one.iterator().next().hashCode(), value.hashCode(), spelling);
        }
        assertEquals(Identity.parse("bücher.example/schémas/x.tn"),
                Identity.parse("https://xn--bcher-kva.example/sch%c3%a9mas/x.tn"));
        assertEquals(Identity.parse("[::1]/x.tn"), Identity.parse("https://[0:0:0:0:0:0:0:1]/x.tn"));
        assertNotEquals(Identity.parse("local/x.tn"), Identity.parse("/local/x.tn"));
    }

    /** The canonical text is the string {@link CanonicalIdentity#canonicalize} returns, for every reference. */
    @Test
    void theTextIsCanonicalize() {
        for (String reference : new String[] {
                "https://tson.io/2026/38/m/core.tn",
                "https://[2001:DB8::1]/x.tn",
                "https://192.0.2.1/x.tn",
                "https://xn--bcher-kva.example/sch%C3%A9mas/a%2fb.tn",
                "file:///local/orders.tn",
                "/local/orders.tn",
                "https://example.com"}) {
            assertEquals(CanonicalIdentity.canonicalize(reference), Identity.ofReference(reference).text(), reference);
            assertEquals(CanonicalIdentity.canonicalize(reference), Identity.parse(reference).text(), reference);
        }
        assertEquals("[2001:db8::1]/x.tn", Identity.parse("[2001:db8::1]/x.tn").text());
    }

    @Test
    void theUriSpellingIsHttpsForAHostAndThePathOtherwise() {
        assertEquals("https://xn--bcher-kva.example/sch%C3%A9mas/x.tn",
                Identity.parse("bücher.example/schémas/x.tn").toUri());
        assertEquals("https://[::1]/x.tn", Identity.parse("[::1]/x.tn").toUri());
        assertEquals("/local/sch%C3%A9mas.tn", Identity.parse("file:/local/schémas.tn").toUri());
    }

    @Test
    void theComponentsAreTheHostValueAndThePath() {
        Identity identity = Identity.parse("https://Example.COM/people.tn");
        assertEquals(Optional.of(Host.parse("example.com")), identity.host());
        assertEquals("/people.tn", identity.path());
        assertEquals(new Identity(Optional.empty(), "/x.tn"), Identity.parse("/x.tn"));
        assertThrows(IllegalArgumentException.class, () -> new Identity(Optional.empty(), "x.tn"));
        assertThrows(IllegalArgumentException.class, () -> new Identity(Optional.of(Host.parse("a.example")), "x"));
    }

    /**
     * The atom's reading refuses a query the loader would; a reference read for its identity alone strips it unread,
     * as {@link CanonicalIdentity#canonicalize} does.
     */
    @Test
    void aQueryIsHashParametersOnly() {
        IdentitySyntaxException e = assertThrows(IdentitySyntaxException.class,
                () -> Identity.parse("https://example.com/x.tn?v=2"));
        assertEquals("carries the query parameter 'v', and a content-address query may contain only hash-algorithm "
                + "parameters", e.reason());
        assertThrows(IdentitySyntaxException.class, () -> Identity.parse("example.com/x.tn?sha256=ABC"));
        assertEquals("example.com/x.tn", Identity.ofReference("https://example.com/x.tn?v=2").text());
        assertEquals(Optional.of(PIN), CanonicalIdentity.declaredSha256("https://example.com/x.tn?sha256=" + PIN));
        assertEquals(Optional.empty(), CanonicalIdentity.declaredSha256("https://example.com/x.tn"));
    }

    /** A refusal quotes the text as written, not the {@code https://} spelling it was read through. */
    @Test
    void aRefusalQuotesTheText() {
        IdentitySyntaxException e = assertThrows(IdentitySyntaxException.class,
                () -> Identity.parse("example.com/a/../b.tn"));
        assertEquals("example.com/a/../b.tn", e.text());
        assertEquals("contains a dot-segment in its path", e.reason());
        SchemaValidationException schema = assertThrows(SchemaValidationException.class,
                () -> CanonicalIdentity.canonicalize("https://example.com/a/../b.tn"));
        assertEquals("'https://example.com/a/../b.tn' contains a dot-segment in its path", schema.getMessage());
    }
}
