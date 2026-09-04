package io.ltr8.tson.compiler.atom;

import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.schema.meta.Cidr6Type;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;

import io.ltr8.tson.schema.atom.CidrNetwork;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code cidr6} (§5.5, RFC 4291 §2.3): an RFC 4291 §2.2 address, {@code /}, and a prefix length of 0-128.
 * {@link Cidr4ParserTest}'s IPv6 twin -- what is exercised here beyond that is the wider prefix range and
 * the address forms only this family has (compression, the IPv4 tail, and a zone identifier's exclusion).
 */
class Cidr6ParserTest {

    private static TokenValue token(String text) {
        return new TokenValue(text, TokenForm.SINGLE_LINE_QUOTED);
    }

    private static Cidr6Parser withPrefixBounds(Integer min, Integer max) {
        return new Cidr6Parser(new Cidr6Type("https://www.rfc-editor.org/rfc/rfc4291",
                Optional.ofNullable(min), Optional.ofNullable(max), List.of(), List.of()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2001:db8::/32",
            "2001:db8::/32",   // the same network, uncompressed
            "fe80::/10",                                     // host bits zero at a non-byte boundary
            "2001:db8:abcd:1234:5678:9abc:def0:1/128",       // a single host
            "::/0",                                          // the whole space
            "::ffff:192.0.2.0/120"})                         // RFC 4291 §2.2's embedded IPv4 tail
    void acceptsWellFormedNetworks(String text) {
        assertEquals(text, Cidr6Parser.UNCONSTRAINED.read(token(text)).text());
    }

    /** The authored text comes back unchanged -- notably, {@code ::} is not expanded on a round trip. */
    @Test
    void returnsTheAuthoredTextAndWritesItBackUnchanged() {
        assertEquals("2001:db8::/32", Cidr6Parser.UNCONSTRAINED.read(token("2001:db8::/32")).text());
        assertEquals("2001:db8::/32", Cidr6Parser.UNCONSTRAINED.write(CidrNetwork.parse("2001:db8::/32", 128)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2001:db8::",            // no prefix at all
            "2001:db8::/",           // empty prefix
            "/32",                   // no address
            "2001:db8::/32/48",      // two slashes
            "2001:db8::/032",        // leading zero
            "2001:db8::/thirty",
            "2001:db8::/1000",       // longer than any family's prefix, so a shape failure
            "2001:db8:::/32",        // Ipv6Parser's own grammar still applies
            "fe80::1%eth0/64",       // zone identifiers are excluded from the contract
            "10.0.0.0/8",            // an IPv4 network, not this family
            ""})
    void rejectsMalformedNetworksAsParseErrors(String text) {
        assertThrows(AtomParseException.class, () -> Cidr6Parser.UNCONSTRAINED.read(token(text)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2001:db8::/129", "2001:db8::/999"})
    void rejectsAPrefixLengthOutsideTheFamilyRangeAsAValidationError(String text) {
        AtomValidationException thrown =
                assertThrows(AtomValidationException.class, () -> Cidr6Parser.UNCONSTRAINED.read(token(text)));
        assertEquals(">= 0 and <= 128", thrown.expected());
    }

    /** A /33 is a validation error for IPv4 and perfectly ordinary here -- the range is per family. */
    @Test
    void acceptsAPrefixTheIpv4FamilyWouldReject() {
        assertEquals("2001:db8:8000::/33", Cidr6Parser.UNCONSTRAINED.read(token("2001:db8:8000::/33")).text());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2001:db8:1::/32",
            "fe80:0040::/10",        // one bit past the prefix, mid-byte
            "::1/0"})
    void rejectsNonzeroHostBitsAsAValidationError(String text) {
        AtomValidationException thrown =
                assertThrows(AtomValidationException.class, () -> Cidr6Parser.UNCONSTRAINED.read(token(text)));
        assertEquals("zero host bits beyond the prefix", thrown.expected());
    }

    @Test
    void appliesThePrefixFacets() {
        Cidr6Parser between32And48 = withPrefixBounds(32, 48);

        assertEquals("2001:db8::/32", between32And48.read(token("2001:db8::/32")).text());
        assertEquals(">= 32", assertThrows(AtomValidationException.class,
                () -> between32And48.read(token("2000::/16"))).expected());
        assertEquals("<= 48", assertThrows(AtomValidationException.class,
                () -> between32And48.read(token("2001:db8:0:1::/64"))).expected());
    }
}
