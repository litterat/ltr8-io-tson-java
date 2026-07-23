package io.ltr8.tson.parser.resolver.vocab;

import io.ltr8.tson.parser.ast.TokenForm;
import io.ltr8.tson.parser.ast.TokenValue;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Ipv4ParserTest {

    private static TokenValue token(String text) {
        return new TokenValue(text, TokenForm.UNQUOTED);
    }

    @Test
    void acceptsCanonicalDottedQuad() throws UnknownHostException {
        assertEquals(InetAddress.getByAddress(new byte[]{(byte) 192, (byte) 168, 0, 1}),
                Ipv4Parser.UNCONSTRAINED.read(token("192.168.0.1")));
    }

    @Test
    void acceptsZeroAndMaxOctets() throws UnknownHostException {
        assertEquals(InetAddress.getByAddress(new byte[]{0, 0, 0, 0}),
                Ipv4Parser.UNCONSTRAINED.read(token("0.0.0.0")));
        assertEquals(InetAddress.getByAddress(new byte[]{(byte) 255, (byte) 255, (byte) 255, (byte) 255}),
                Ipv4Parser.UNCONSTRAINED.read(token("255.255.255.255")));
    }

    @Test
    void rejectsLeadingZero() {
        // InetAddress.ofLiteral itself would accept this -- RFC 3986's dec-octet forbids it.
        assertThrows(AtomParseException.class, () -> Ipv4Parser.UNCONSTRAINED.read(token("0177.0.0.1")));
        assertThrows(AtomParseException.class, () -> Ipv4Parser.UNCONSTRAINED.read(token("010.0.0.1")));
    }

    @Test
    void rejectsShortForm() {
        // InetAddress.ofLiteral itself would accept "1.2.3" as the legacy class-based 1.2.0.3.
        assertThrows(AtomParseException.class, () -> Ipv4Parser.UNCONSTRAINED.read(token("1.2.3")));
    }

    @Test
    void rejectsBareIntegerForm() {
        // InetAddress.ofLiteral itself would accept this as 192.168.0.1.
        assertThrows(AtomParseException.class, () -> Ipv4Parser.UNCONSTRAINED.read(token("3232235521")));
    }

    @Test
    void rejectsOutOfRangeOctet() {
        assertThrows(AtomParseException.class, () -> Ipv4Parser.UNCONSTRAINED.read(token("256.0.0.1")));
    }

    @Test
    void rejectsTooFewOrTooManyOctets() {
        assertThrows(AtomParseException.class, () -> Ipv4Parser.UNCONSTRAINED.read(token("1.2.3.4.5")));
        assertThrows(AtomParseException.class, () -> Ipv4Parser.UNCONSTRAINED.read(token("1.2.3")));
    }

    @Test
    void rejectsIpv6Text() {
        assertThrows(AtomParseException.class, () -> Ipv4Parser.UNCONSTRAINED.read(token("::1")));
    }

    @Test
    void writeUsesGetHostAddressNotToString() {
        // Regression check: Inet4Address#toString() prepends a stray "/".
        String written = Ipv4Parser.UNCONSTRAINED.write(Ipv4Parser.UNCONSTRAINED.read(token("192.168.0.1")));
        assertEquals("192.168.0.1", written);
    }
}
