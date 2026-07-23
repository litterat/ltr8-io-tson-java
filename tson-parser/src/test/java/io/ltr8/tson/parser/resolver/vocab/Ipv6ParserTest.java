package io.ltr8.tson.parser.resolver.vocab;

import io.ltr8.tson.parser.ast.TokenForm;
import io.ltr8.tson.parser.ast.TokenValue;
import org.junit.jupiter.api.Test;

import java.net.Inet6Address;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Ipv6ParserTest {

    private static TokenValue token(String text) {
        return new TokenValue(text, TokenForm.UNQUOTED);
    }

    private static Inet6Address literal(String hex) throws UnknownHostException {
        return Inet6Address.getByAddress(null, hexToBytes(hex), -1);
    }

    private static byte[] hexToBytes(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    @Test
    void acceptsFullPreferredForm() throws UnknownHostException {
        assertEquals(literal("00010002000300040005000600070008"),
                Ipv6Parser.UNCONSTRAINED.read(token("1:2:3:4:5:6:7:8")));
    }

    @Test
    void acceptsUnspecifiedAddress() throws UnknownHostException {
        assertEquals(literal("00000000000000000000000000000000"),
                Ipv6Parser.UNCONSTRAINED.read(token("::")));
    }

    @Test
    void acceptsLoopback() throws UnknownHostException {
        assertEquals(literal("00000000000000000000000000000001"),
                Ipv6Parser.UNCONSTRAINED.read(token("::1")));
    }

    @Test
    void acceptsTrailingCompression() throws UnknownHostException {
        assertEquals(literal("00010000000000000000000000000000"),
                Ipv6Parser.UNCONSTRAINED.read(token("1::")));
    }

    @Test
    void acceptsMidAddressCompression() throws UnknownHostException {
        assertEquals(literal("20010db8000000000000000000000001"),
                Ipv6Parser.UNCONSTRAINED.read(token("2001:db8::1")));
    }

    @Test
    void acceptsCompressionRepresentingExactlyOneGroup() throws UnknownHostException {
        assertEquals(literal("00010002000000040005000600070008"),
                Ipv6Parser.UNCONSTRAINED.read(token("1:2::4:5:6:7:8")));
    }

    @Test
    void acceptsUppercaseHexDigits() throws UnknownHostException {
        assertEquals(literal("20010db8000000000000000000000001"),
                Ipv6Parser.UNCONSTRAINED.read(token("2001:DB8::1")));
    }

    @Test
    void acceptsLeadingZerosWithinAHexGroup() throws UnknownHostException {
        // Unlike ipv4's decimal octets, a hex group's own leading zeros are fine -- it's a digit
        // count restriction (1-4), not a leading-zero prohibition.
        assertEquals(literal("00000000000000000000000000000001"),
                Ipv6Parser.UNCONSTRAINED.read(token("0000:0000:0000:0000:0000:0000:0000:0001")));
    }

    @Test
    void acceptsIpv4MappedForm() throws UnknownHostException {
        assertEquals(literal("00000000000000000000ffffc0000201"),
                Ipv6Parser.UNCONSTRAINED.read(token("::ffff:192.0.2.1")));
    }

    @Test
    void ipv4MappedFormStillReturnsInet6AddressNotInet4Address() {
        // InetAddress.getByAddress(byte[16]) itself would silently return an Inet4Address for
        // exactly this byte pattern -- confirmed empirically before deciding to route around it.
        assertInstanceOf(Inet6Address.class, Ipv6Parser.UNCONSTRAINED.read(token("::ffff:192.0.2.1")));
    }

    @Test
    void acceptsIpv4TailFormWithoutCompression() throws UnknownHostException {
        assertEquals(literal("00010002000300040005000601020304"),
                Ipv6Parser.UNCONSTRAINED.read(token("1:2:3:4:5:6:1.2.3.4")));
    }

    @Test
    void rejectsMoreThanOneCompressionRun() {
        assertThrows(AtomParseException.class, () -> Ipv6Parser.UNCONSTRAINED.read(token("1::2::3")));
        assertThrows(AtomParseException.class, () -> Ipv6Parser.UNCONSTRAINED.read(token("1:::2")));
    }

    @Test
    void rejectsTooFewGroupsWithoutCompression() {
        assertThrows(AtomParseException.class, () -> Ipv6Parser.UNCONSTRAINED.read(token("1:2:3:4:5:6:7")));
    }

    @Test
    void rejectsTooManyGroups() {
        assertThrows(AtomParseException.class, () -> Ipv6Parser.UNCONSTRAINED.read(token("1:2:3:4:5:6:7:8:9")));
        assertThrows(AtomParseException.class, () -> Ipv6Parser.UNCONSTRAINED.read(token("1:2:3:4:5:6:7::8")));
    }

    @Test
    void rejectsGroupWithTooManyHexDigits() {
        assertThrows(AtomParseException.class, () -> Ipv6Parser.UNCONSTRAINED.read(token("12345::")));
    }

    @Test
    void rejectsIpv4TailNotAtTheEnd() {
        assertThrows(AtomParseException.class, () -> Ipv6Parser.UNCONSTRAINED.read(token("1.2.3.4::5")));
    }

    @Test
    void rejectsIpv4TailWithLeadingZero() {
        // Same RFC 3986 dec-octet strictness as Ipv4Parser itself, reused here.
        assertThrows(AtomParseException.class, () -> Ipv6Parser.UNCONSTRAINED.read(token("::ffff:192.0.02.1")));
    }

    @Test
    void rejectsZoneIdentifier() {
        // core.tn1: zone identifiers are host-local and excluded from the contract.
        assertThrows(AtomParseException.class, () -> Ipv6Parser.UNCONSTRAINED.read(token("fe80::1%eth0")));
    }

    @Test
    void rejectsEmptyToken() {
        assertThrows(AtomParseException.class, () -> Ipv6Parser.UNCONSTRAINED.read(token("")));
    }

    @Test
    void rejectsIpv4Text() {
        assertThrows(AtomParseException.class, () -> Ipv6Parser.UNCONSTRAINED.read(token("192.168.0.1")));
    }

    @Test
    void writeUsesGetHostAddressNotToString() throws UnknownHostException {
        // Regression check: Inet6Address#toString() prepends a stray "/". Writes the uncompressed
        // form (getHostAddress() doesn't apply RFC 5952's "::" canonicalization) -- still valid
        // per Ipv6Parser's own read() grammar, just not the shortest legal spelling.
        String written = Ipv6Parser.UNCONSTRAINED.write(Ipv6Parser.UNCONSTRAINED.read(token("2001:db8::1")));
        assertEquals("2001:db8:0:0:0:0:0:1", written);
        assertEquals(literal("20010db8000000000000000000000001"), Ipv6Parser.UNCONSTRAINED.read(token(written)));
    }
}
