package io.ltr8.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The address, network and EUI-48 grammars, each against its RFC and against the JDK's laxer reading. */
class AddressFormsTest {

    @Test
    void ipv4IsFourDecOctets() {
        assertArrayEquals(new byte[] {(byte) 192, 0, 2, 1}, InternetAddress.ipv4("192.0.2.1"));
        // InetAddress.ofLiteral admits every one of these; RFC 3986's IPv4address admits none.
        for (String lax : List.of("0177.0.0.1", "1.2.3", "3232235521", "256.0.0.1", "1.2.3.4.5", "")) {
            assertNull(InternetAddress.ipv4(lax), lax);
        }
        assertEquals("192.0.2.1", InternetAddress.ipv4Text(InternetAddress.ipv4("192.0.2.1")));
    }

    @Test
    void ipv6IsRfc4291sTextFormsAndWritesRfc5952s() {
        assertNotNull(InternetAddress.ipv6("2001:DB8:0:0:0:0:0:1"));
        assertEquals("2001:db8::1", InternetAddress.ipv6Text(InternetAddress.ipv6("2001:DB8:0:0:0:0:0:1")));
        assertEquals("::ffff:192.0.2.1", InternetAddress.ipv6Text(InternetAddress.ipv6("::ffff:192.0.2.1")));
        for (String bad : List.of("1::2::3", "1:2:3:4:5:6:7:8:9", "12345::", "1:2:3:4:5:6:7:8::", "[::1]", "::1%eth0")) {
            assertNull(InternetAddress.ipv6(bad), bad);
        }
    }

    @Test
    void aCidrNetworkIsAValueWithContainment() {
        CidrInet4Network ten = CidrInet4Network.parse("10.0.0.0/8");
        assertEquals(ten, CidrInet4Network.parse("10.0.0.0/8"));
        assertTrue(ten.contains(CidrInet4Network.parse("10.1.0.0/16")));
        assertFalse(CidrInet4Network.parse("10.1.0.0/16").contains(ten));
        assertTrue(ten.hostBitsAreZero());
        assertFalse(CidrInet4Network.parse("10.1.0.0/8").hostBitsAreZero());
        assertNull(CidrInet4Network.parse("10.0.0.0/33"));
        assertNull(CidrInet4Network.parse("10.0.0.0"));
        assertEquals(CidrInet6Network.parse("2001:db8::/32"), CidrInet6Network.parse("2001:0db8:0000::/32"));
    }

    @Test
    void eui48IsSixOctetsWithOneSeparator() {
        assertArrayEquals(new byte[] {(byte) 0xAA, (byte) 0xBB, (byte) 0xCC, (byte) 0xDD, (byte) 0xEE, (byte) 0xFF},
                MacAddress.eui48("aa-bb-cc-dd-ee-ff"));
        assertNotNull(MacAddress.eui48("AA:BB:CC:DD:EE:FF"));
        for (String bad : List.of("AA-BB:CC-DD:EE-FF", "AA:BB:CC:DD:EE", "AA:BB:CC:DD:EE:FG", "AABBCCDDEEFF",
                "００:00:00:00:00:00")) {
            assertNull(MacAddress.eui48(bad), bad);
        }
    }
}
