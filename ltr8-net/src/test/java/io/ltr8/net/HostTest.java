package io.ltr8.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A host name or an IP address, each member keeping its own equality. */
class HostTest {

    private static String refused(String text) {
        return assertThrows(HostSyntaxException.class, () -> Host.parse(text)).getMessage();
    }

    @Test
    void theThreeMembers() {
        assertInstanceOf(HostName.class, Host.parse("localhost"));
        assertEquals("127.0.0.1", Host.parse("127.0.0.1").text());
        assertEquals("::1", Host.parse("::1").text());
    }

    @Test
    void anIpv6AddressComparesByItsOctets() {
        assertEquals(Host.parse("::1"), Host.parse("0:0:0:0:0:0:0:1"));
        assertEquals("::1", Host.parse("0:0:0:0:0:0:0:1").text());
    }

    @Test
    void bracketsAreASecondSpellingWrittenBackBare() {
        assertEquals(Host.parse("::1"), Host.parse("[::1]"));
        assertEquals("::1", Host.parse("[::1]").text());
    }

    @Test
    void theFamiliesNeverMeet() {
        assertNotEquals(Host.parse("127.0.0.1"), Host.parse("::ffff:127.0.0.1"));
        assertNotEquals(Host.parse("localhost"), Host.parse("127.0.0.1"));
    }

    @Test
    void aNameComparesByName() {
        assertEquals(Host.parse("xn--bcher-kva.example"), Host.parse("bücher.example"));
    }

    @Test
    void refusals() {
        assertTrue(refused("fe80::1%eth0").contains("zone identifier"));
        assertTrue(refused("[fe80::1%25eth0]").contains("zone identifier"));
        assertTrue(refused("[::1").contains("IPv6"));
        assertTrue(refused("1::2::3").contains("IPv6"));
        assertTrue(refused("256.0.0.1").contains("IPv4"));
        assertTrue(refused("_bad.example").contains("'_'"));
    }
}
