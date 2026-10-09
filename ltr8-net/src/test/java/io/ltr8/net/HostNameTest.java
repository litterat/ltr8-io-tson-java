package io.ltr8.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A host name in either label form, one value per name. Characters beyond ASCII are escapes. */
class HostNameTest {

    /** {@code bücher.example}. */
    private static final String BUCHER = "bücher.example";

    private static String refused(String text) {
        return assertThrows(HostSyntaxException.class, () -> HostName.parse(text)).getMessage();
    }

    @Test
    void anLdhNameIsItselfLowercased() {
        HostName name = HostName.parse("Api.Example.COM");
        assertEquals("api.example.com", name.unicode());
        assertEquals("api.example.com", name.ascii());
        assertFalse(name.isIdn());
        assertEquals(HostName.parse("api.example.com"), name);
    }

    @Test
    void aULabelAndItsALabelAreOneName() {
        HostName unicode = HostName.parse(BUCHER);
        HostName ascii = HostName.parse("xn--bcher-kva.example");
        assertEquals(unicode, ascii);
        assertEquals(unicode.hashCode(), ascii.hashCode());
        assertEquals(BUCHER, ascii.unicode());
        assertEquals("xn--bcher-kva.example", unicode.ascii());
        assertTrue(unicode.isIdn() && ascii.isIdn());
        assertEquals(BUCHER, ascii.toString());
    }

    @Test
    void anALabelFoldsAsciiCase() {
        assertEquals(BUCHER, HostName.parse("XN--BCHER-KVA.Example").unicode());
    }

    /** {@code ตัวอย่าง.ไทย}, IANA's Thai test name. */
    @Test
    void aThaiName() {
        HostName thai = HostName.parse("ตัวอย่าง.ไทย");
        assertTrue(thai.ascii().startsWith("xn--"));
        assertEquals(thai, HostName.parse(thai.ascii()));
    }

    @Test
    void anArabicNameMeetsTheBidiRule() {
        assertTrue(HostName.parse("مثال.example").isIdn());
        assertTrue(refused("1abc.مثال").contains("Bidi rule"));
    }

    @Test
    void uppercaseBeyondAsciiIsRefusedWithItsFix() {
        String message = refused("BÜCHER.example");
        assertTrue(message.contains("uppercase U+00DC"), message);
        assertTrue(message.contains("write '" + BUCHER + "'"), message);
    }

    @Test
    void aFullwidthSpellingIsRefused() {
        assertTrue(refused("ｅxample.com").contains("U+FF45"));
    }

    @Test
    void aDecomposedULabelIsRefusedWithItsComposedForm() {
        String message = refused("bücher.example");
        assertTrue(message.contains("Normalization Form C"), message);
        assertTrue(message.contains("write '" + BUCHER + "'"), message);
    }

    @Test
    void theBoundaries() {
        assertTrue(refused("example.com.").contains("write 'example.com'"));
        assertTrue(refused("b%C3%BCcher.example").contains("percent-encoded"));
        assertTrue(refused("192.0.2.1").contains("IPv4 address"));
        assertTrue(refused("_dmarc.example.com").contains("'_'"));
        assertTrue(refused("example-.com").contains("hyphen"));
        assertTrue(refused("ab--cd.example").contains("reserved for A-labels"));
        assertTrue(refused("a..b").contains("empty label"));
        assertTrue(refused("").contains("empty"));
    }

    @Test
    void lengthsAreCountedInTheALabelForm() {
        assertTrue(refused("a".repeat(64) + ".example").contains("at most 63"));
        HostName.parse("a".repeat(63) + ".example");
        String long1 = ("a".repeat(63) + ".").repeat(3) + "a".repeat(61);
        assertEquals(253, HostName.parse(long1).ascii().length());
        assertTrue(refused(long1 + "a").contains("at most 253"));
    }

    @Test
    void aFakeALabelIsRefused() {
        assertTrue(refused("xn--abc.example").contains("not an A-label")
                || refused("xn--abc.example").contains("U-label"));
        assertTrue(refused("xn--example-.example").contains("not an A-label"));
    }

    @Test
    void contextualCharactersFollowTheirRules() {
        HostName.parse("क्‍ष.example");
        assertTrue(refused("a‍b.example").contains("contextual rule"));
    }

    @Test
    void aDigitMayLeadAnyLabelButTheLast() {
        assertEquals("3com.example", HostName.parse("3com.example").unicode());
        assertEquals("localhost", HostName.parse("localhost").unicode());
        assertNotEquals(HostName.parse("a.example"), HostName.parse("b.example"));
    }
}
