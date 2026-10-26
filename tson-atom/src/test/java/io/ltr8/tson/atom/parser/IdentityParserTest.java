package io.ltr8.tson.atom.parser;

import io.ltr8.tson.atom.AtomParseException;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.BuiltinTypeVocabulary;
import io.ltr8.tson.base.Identity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * core.tn's {@code identity} ([TSON-DATA] §2.2.1): a canonical identity, read from its own text or from any reference
 * naming it, written as the canonical identity.
 */
class IdentityParserTest {

    private static final IdentityParser IDENTITY = IdentityParser.UNCONSTRAINED;

    private static final String PIN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @ParameterizedTest
    @ValueSource(strings = {
            "tson.io/2026/38/m/core.tn",                          // the canonical identity itself
            "https://tson.io/2026/38/m/core.tn",
            "http://tson.io/2026/38/m/core.tn",
            "HTTPS://TSON.IO/2026/38/m/core.tn",                  // scheme and host in any ASCII case
            "https://tson.io/2026/38/m/core.tn?sha256=" + PIN,    // pinned
            "//tson.io/2026/38/m/core.tn"})                       // a network-path reference
    void everySpellingOfOneIdentityIsOneValue(String text) {
        Identity value = IDENTITY.read(text);
        assertEquals("tson.io/2026/38/m/core.tn", value.text());
        assertEquals(IDENTITY.read("tson.io/2026/38/m/core.tn"), value);
        assertEquals("tson.io/2026/38/m/core.tn", IDENTITY.write(value));
    }

    @Test
    void aHostIsAHostValueAndAPathIsReadBackFromItsUriSpelling() {
        assertEquals("bücher.example/schémas/x.tn",
                IDENTITY.read("https://xn--bcher-kva.example/sch%c3%a9mas/x.tn").text());
        assertEquals(IDENTITY.read("bücher.example/schémas/x.tn"),
                IDENTITY.read("http://XN--BCHER-KVA.example/sch%C3%A9mas/x.tn"));
        assertEquals("example.com/people.tn", IDENTITY.read("Example.COM/people.tn").text());
        assertEquals("[2001:db8::1]/x.tn", IDENTITY.read("[2001:DB8:0:0:0:0:0:1]/x.tn").text());
        assertEquals("192.0.2.1/x.tn", IDENTITY.read("192.0.2.1/x.tn").text());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/local/orders.tn", "file:/local/orders.tn", "file:///local/orders.tn"})
    void aPathOnlyIdentityIsItsAbsolutePath(String text) {
        Identity value = IDENTITY.read(text);
        assertEquals("/local/orders.tn", value.text());
        assertTrue(value.host().isEmpty());
    }

    @Test
    void aHostedIdentityNeverEqualsAPathOnlyOne() {
        assertNotEquals(IDENTITY.read("local/orders.tn"), IDENTITY.read("/local/orders.tn"));
        assertEquals("local/orders.tn", IDENTITY.read("local/orders.tn").text());
    }

    /** Each refusal names the rule, in {@code CanonicalIdentity}'s own words. */
    @ParameterizedTest
    @ValueSource(strings = {
            "file:orders.tn",                                     // no host, a relative path
            "https://tson.io/2026/38/m/core.tn#types",            // a fragment
            "https://tson.io:443/2026/38/m/core.tn",              // a port
            "https://user@tson.io/2026/38/m/core.tn",             // userinfo
            "https://tson.io/2026/../m/core.tn",                  // a dot-segment
            "https://[v1.x]/x.tn",                                // IPvFuture
            "https://example.com/%FF.tn",                         // no IRI form
            "https://example.com/%41.tn",                         // an encoded unreserved character
            "https://example.com/schémas/x.tn",             // not NFC
            "https://_x.example/x.tn",                            // not a host name
            "https://tson.io/2026/38/m/core.tn?md5=abc",          // a query that is not a hash parameter
            "https://tson.io/2026/38/m/core.tn?sha256=abc",       // a truncated pin
            "exa mple.com/x.tn"})                                 // not an IRI-reference
    void whatNamesNoIdentityIsRefused(String text) {
        AtomParseException e = assertThrows(AtomParseException.class, () -> IDENTITY.read(text));
        assertTrue(e.getMessage().startsWith("'" + text + "' is not an identity: it "), e.getMessage());
    }

    @Test
    void aRefusalStatesTheRule() {
        AtomParseException e = assertThrows(AtomParseException.class,
                () -> IDENTITY.read("https://tson.io:443/2026/38/m/core.tn"));
        assertEquals("'https://tson.io:443/2026/38/m/core.tn' is not an identity: it carries a port, not permitted "
                + "in an identifying URI", e.getMessage());
    }

    /** A {@code String} target receives the canonical identity, the string the library keys its registries by. */
    @Test
    @SuppressWarnings("unchecked")
    void aStringTargetReceivesTheCanonicalIdentity() {
        AtomType<String> text = (AtomType<String>) IDENTITY.boundTo(String.class).orElseThrow();
        assertEquals("tson.io/2026/38/m/core.tn", text.read("http://Tson.IO/2026/38/m/core.tn?sha256=" + PIN));
        assertSame(IDENTITY, IDENTITY.boundTo(Identity.class).orElseThrow());
        assertTrue(IDENTITY.boundTo(Integer.class).isEmpty());
    }

    /** A schemaless {@code !identity} denotes core's type ([TSON-DATA] §5.5). */
    @Test
    void theNameIsInTheBuiltinVocabulary() {
        assertSame(IDENTITY, BuiltinTypeVocabulary.lookup("identity").orElseThrow());
    }
}
