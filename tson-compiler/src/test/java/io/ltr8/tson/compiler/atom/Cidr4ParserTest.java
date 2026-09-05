package io.ltr8.tson.compiler.atom;

import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.schema.meta.Cidr4Type;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;

import io.ltr8.tson.schema.atom.CidrNetwork;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code cidr4} (§5.5, RFC 4632): a dotted-quad address, {@code /}, and a prefix length of 0-32. The two
 * failure categories are the spec's own -- a token that isn't CIDR-shaped is a parse error, a prefix length
 * outside the family range or an address with nonzero host bits is a validation error -- so the tests assert
 * which one, not merely that something was rejected.
 */
class Cidr4ParserTest {

    private static TokenValue token(String text) {
        return new TokenValue(text, TokenForm.SINGLE_LINE_QUOTED);
    }

    private static Cidr4Parser withPrefixBounds(Integer min, Integer max) {
        return new Cidr4Parser(new Cidr4Type("https://www.rfc-editor.org/rfc/rfc4632",
                Optional.ofNullable(min), Optional.ofNullable(max), List.of(), List.of()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "10.0.0.0/8",
            "192.168.0.0/16",
            "192.0.2.128/25",        // host bits zero at a non-byte boundary
            "203.0.113.5/32",        // a single host is a valid /32 network
            "0.0.0.0/0"})            // the whole space
    void acceptsWellFormedNetworks(String text) {
        assertEquals(text, Cidr4Parser.UNCONSTRAINED.read(token(text)).text());
    }

    /** The authored text comes back unchanged, so a read/write round trip is exact. */
    @Test
    void returnsTheAuthoredTextAndWritesItBackUnchanged() {
        assertEquals("10.0.0.0/8", Cidr4Parser.UNCONSTRAINED.read(token("10.0.0.0/8")).text());
        assertEquals("10.0.0.0/8", Cidr4Parser.UNCONSTRAINED.write(CidrNetwork.parse("10.0.0.0/8", 32)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "10.0.0.0",              // no prefix at all
            "10.0.0.0/",             // empty prefix
            "/8",                    // no address
            "10.0.0.0/8/16",         // two slashes
            "10.0.0.0/08",           // leading zero -- a second spelling of /8
            "10.0.0.0/+8",
            "10.0.0.0/ 8",
            "10.0.0.0/eight",
            "10.0.0.0/1000",         // longer than any family's prefix, so a shape failure
            "0177.0.0.0/8",          // Ipv4Parser's leading-zero octet rule still applies
            "10.0.0/8",              // BSD short form
            "2001:db8::/32",         // an IPv6 network, not this family
            ""})
    void rejectsMalformedNetworksAsParseErrors(String text) {
        assertThrows(AtomParseException.class, () -> Cidr4Parser.UNCONSTRAINED.read(token(text)));
    }

    /** §5.5: "a CIDR prefix length outside the address family's range is a validation error". */
    @ParameterizedTest
    @ValueSource(strings = {"10.0.0.0/33", "10.0.0.0/128", "10.0.0.0/999"})
    void rejectsAPrefixLengthOutsideTheFamilyRangeAsAValidationError(String text) {
        AtomValidationException thrown =
                assertThrows(AtomValidationException.class, () -> Cidr4Parser.UNCONSTRAINED.read(token(text)));
        assertEquals(">= 0 and <= 32", thrown.expected());
    }

    /** §5.5: the host value is a network, and accept-and-mask would be lossy, so nonzero host bits fail. */
    @ParameterizedTest
    @ValueSource(strings = {
            "10.1.0.0/8",
            "192.0.2.1/24",
            "192.0.2.129/25",        // one bit past the prefix, mid-byte
            "10.0.0.1/0"})
    void rejectsNonzeroHostBitsAsAValidationError(String text) {
        AtomValidationException thrown =
                assertThrows(AtomValidationException.class, () -> Cidr4Parser.UNCONSTRAINED.read(token(text)));
        assertEquals("zero host bits beyond the prefix", thrown.expected());
    }

    @Test
    void appliesTheMinPrefixFacet() {
        Cidr4Parser atLeast16 = withPrefixBounds(16, null);

        assertEquals("192.168.0.0/16", atLeast16.read(token("192.168.0.0/16")).text());
        AtomValidationException thrown =
                assertThrows(AtomValidationException.class, () -> atLeast16.read(token("10.0.0.0/8")));
        assertEquals(">= 16", thrown.expected());
    }

    @Test
    void appliesTheMaxPrefixFacet() {
        Cidr4Parser atMost24 = withPrefixBounds(null, 24);

        assertEquals("192.0.2.0/24", atMost24.read(token("192.0.2.0/24")).text());
        AtomValidationException thrown =
                assertThrows(AtomValidationException.class, () -> atMost24.read(token("192.0.2.128/25")));
        assertEquals("<= 24", thrown.expected());
    }

    /**
     * A declared bound outside the family range is inert rather than an error here -- checking it is a
     * constraint-family coherence rule, and the family range is enforced regardless, so a wider bound can
     * never widen what this accepts. See {@link Cidr4Parser}'s own Javadoc.
     */
    @Test
    void aPrefixBoundOutsideTheFamilyRangeNeitherFailsNorWidens() {
        Cidr4Parser atMost64 = withPrefixBounds(null, 64);

        assertEquals("192.0.2.0/24", atMost64.read(token("192.0.2.0/24")).text());
        assertThrows(AtomValidationException.class, () -> atMost64.read(token("10.0.0.0/33")));
    }
}
