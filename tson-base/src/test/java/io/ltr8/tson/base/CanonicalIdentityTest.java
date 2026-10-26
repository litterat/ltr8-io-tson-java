package io.ltr8.tson.base;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalIdentityTest {

    @Test
    void stripsSchemeAndDelimiterFromTheRealMetaKernelId() {
        Assertions.assertEquals("tson.io/2026/38/m/meta-kernel.tn",
                CanonicalIdentity.canonicalize("https://tson.io/2026/38/m/meta-kernel.tn"));
    }

    @Test
    void httpAndHttpsResolveToTheSameIdentity() {
        assertEquals(CanonicalIdentity.canonicalize("https://tson.io/2026/38/m/meta-kernel.tn"),
                CanonicalIdentity.canonicalize("http://tson.io/2026/38/m/meta-kernel.tn"));
    }

    @Test
    void queryIsDropped() {
        assertEquals("tson.io/2026/38/m/meta-kernel.tn",
                CanonicalIdentity.canonicalize("https://tson.io/2026/38/m/meta-kernel.tn?sha256=abc123"));
    }

    /** A host is a host-name value, so a reference in any ASCII case names one identity; an !!id must be lowercase. */
    @Test
    void aHostComparesWithoutAsciiCase() {
        assertEquals("tson.io/2026/38/m/meta-kernel.tn",
                CanonicalIdentity.canonicalize("https://Tson.IO/2026/38/m/meta-kernel.tn"));
        SchemaValidationException e = assertThrows(SchemaValidationException.class,
                () -> CanonicalIdentity.validate("https://Tson.io/2026/38/m/meta-kernel.tn"));
        assertTrue(e.getMessage().contains("write 'https://tson.io/2026/38/m/meta-kernel.tn'"), e.getMessage());
    }

    @Test
    void rejectsDotSegmentInPath() {
        assertThrows(SchemaValidationException.class,
                () -> CanonicalIdentity.canonicalize("https://tson.io/2026/../m/meta-kernel.tn"));
    }

    @Test
    void rejectsUserinfo() {
        assertThrows(SchemaValidationException.class,
                () -> CanonicalIdentity.canonicalize("https://user@tson.io/2026/38/m/meta-kernel.tn"));
    }

    @Test
    void rejectsExplicitPort() {
        assertThrows(SchemaValidationException.class,
                () -> CanonicalIdentity.canonicalize("https://tson.io:443/2026/38/m/meta-kernel.tn"));
    }

    @Test
    void rejectsFragment() {
        assertThrows(SchemaValidationException.class,
                () -> CanonicalIdentity.canonicalize("https://tson.io/2026/38/m/meta-kernel.tn#section"));
    }

    @Test
    void rejectsPercentEncodedUnreservedCharacter() {
        // %7E decodes to '~', an unreserved character -- MUST NOT be percent-encoded.
        assertThrows(SchemaValidationException.class,
                () -> CanonicalIdentity.canonicalize("https://tson.io/2026/38/m/meta-kernel%7E.tn"));
    }

    @Test
    void allowsPercentEncodingOfAReservedCharacter() {
        // %2F decodes to '/', a reserved character -- percent-encoding it is not forbidden.
        assertEquals("tson.io/2026%2F32/m/meta-kernel.tn",
                CanonicalIdentity.canonicalize("https://tson.io/2026%2F32/m/meta-kernel.tn"));
    }

    /**
     * The identity is the IRI ([TSON-DATA] §2.2.1, §3.3): a host and path beyond US-ASCII are an identity, and the
     * reference's URI spelling -- A-labels, percent-encoded UTF-8 in either hex case -- names the same one.
     */
    @Test
    void theUriSpellingNamesTheSameIdentity() {
        assertEquals("\u4F8B\u3048.test/\u6CE8\u6587.tn",
                CanonicalIdentity.canonicalize("https://\u4F8B\u3048.test/\u6CE8\u6587.tn"));
        assertEquals("example.test/\u6CE8.tn", CanonicalIdentity.canonicalize("https://example.test/%E6%B3%A8.tn"));
        assertEquals("example.test/\u6CE8.tn", CanonicalIdentity.canonicalize("https://example.test/%e6%b3%a8.tn"));
        assertTrue(CanonicalIdentity.sameIdentity("https://b\u00FCcher.example/sch\u00E9mas/x.tn",
                "http://xn--bcher-kva.example/sch%C3%A9mas/x.tn?sha256=" + "0".repeat(64)));
    }

    @Test
    void theUriSpellingIsTheOtherDirection() {
        assertEquals("https://xn--bcher-kva.example/sch%C3%A9mas/x.tn?sha256=ab",
                CanonicalIdentity.toUri("https://b\u00FCcher.example/sch\u00E9mas/x.tn?sha256=ab"));
        assertEquals("https://[2001:db8::1]/x.tn", CanonicalIdentity.toUri("https://[2001:DB8:0:0:0:0:0:1]/x.tn"));
        assertEquals("/local/orders.tn", CanonicalIdentity.toUri("/local/orders.tn"));
    }

    @Test
    void anAddressHostComparesByItsOctets() {
        assertEquals("[2001:db8::1]/x.tn", CanonicalIdentity.canonicalize("https://[2001:db8:0:0:0:0:0:1]/x.tn"));
        assertEquals("192.0.2.1/x.tn", CanonicalIdentity.canonicalize("https://192.0.2.1/x.tn"));
    }

    @Test
    void aReservedEncodingIsKeptInUppercaseHex() {
        assertEquals("example.com/a%2Fb.tn", CanonicalIdentity.canonicalize("https://example.com/a%2fb.tn"));
    }

    /** What has no IRI form, or is no host, names no identity. */
    @Test
    void whatHasNoIriFormIsRefused() {
        for (String reference : new String[] {
                "https://example.com/%FF.tn",                    // not UTF-8
                "https://example.com/%E2%80%8F.tn",              // an encoded bidi control
                "https://example.com/sche\u0301mas/x.tn",        // not NFC
                "https://example.com/sche%CC%81mas/x.tn",        // not NFC, encoded
                "https://_x.example/x.tn",                       // not a host name
                "https://b%C3%BCcher.example/x.tn",              // a percent-encoded host
                "https://B\u00DCCHER.example/x.tn",              // uppercase beyond ASCII
                "https://[v1.x]/x.tn",                           // IPvFuture
                "https://[fe80::1%25eth0]/x.tn",                 // a zone: not even an IRI-reference
                "https://example.com/%41.tn",                    // an encoded unreserved character
        }) {
            assertThrows(SchemaValidationException.class, () -> CanonicalIdentity.canonicalize(reference), reference);
        }
    }

    /** A document's own name has one spelling: the canonical one, which the refusal names. */
    @Test
    void anIdIsWrittenCanonically() {
        CanonicalIdentity.validate("https://b\u00FCcher.example/sch\u00E9mas/x.tn?sha256=ab");
        CanonicalIdentity.validate("https://[2001:db8::1]/x.tn");
        CanonicalIdentity.validate("https://example.com/a%2Fb.tn");
        for (String id : new String[] {
                "https://xn--bcher-kva.example/x.tn",
                "https://example.com/sch%C3%A9mas/x.tn",
                "https://example.com/a%2fb.tn",
                "https://[2001:db8:0:0:0:0:0:1]/x.tn",
        }) {
            assertThrows(SchemaValidationException.class, () -> CanonicalIdentity.validate(id), id);
        }
        SchemaValidationException e = assertThrows(SchemaValidationException.class,
                () -> CanonicalIdentity.validate("https://xn--bcher-kva.example/sch%C3%A9mas/x.tn"));
        assertTrue(e.getMessage().contains("write 'https://b\u00FCcher.example/sch\u00E9mas/x.tn'"), e.getMessage());
    }

    /**
     * A reference with no host names a library entry by its path (§2.2.1), which must be absolute so that it is never
     * an identity a host and path also spell.
     */
    @Test
    void aPathOnlyIdentityIsItsAbsolutePath() {
        assertEquals("/local/orders.tn", CanonicalIdentity.canonicalize("/local/orders.tn"));
        assertEquals("/local/orders.tn", CanonicalIdentity.canonicalize("file:/local/orders.tn"));
        assertEquals("/local/orders.tn", CanonicalIdentity.canonicalize("file:///local/orders.tn"));
        assertEquals("/local/orders.tn", CanonicalIdentity.canonicalize("/local/orders.tn?sha256=abc"));
        assertThrows(SchemaValidationException.class, () -> CanonicalIdentity.canonicalize("mini.tn"));
        assertThrows(SchemaValidationException.class, () -> CanonicalIdentity.canonicalize("/local/../orders.tn"));
        assertThrows(SchemaValidationException.class, () -> CanonicalIdentity.canonicalize("/local/orders.tn#x"));
    }

    @Test
    void rejectsMissingScheme() {
        assertThrows(SchemaValidationException.class,
                () -> CanonicalIdentity.canonicalize("tson.io/2026/38/m/meta-kernel.tn"));
    }

    @Test
    void rejectsMissingHost() {
        assertThrows(SchemaValidationException.class,
                () -> CanonicalIdentity.canonicalize("mailto:someone@example.com"));
    }

    @Test
    void rejectsAUriCarryingAPort() {
        assertThrows(SchemaValidationException.class,
                () -> CanonicalIdentity.canonicalize("https://example.test:8080/registry-test.tn"));
    }

    @Test
    void validateAcceptsAWellFormedCandidateSilently() {
        CanonicalIdentity.validate("https://example.test/registry-test.tn");
        // No exception -- that's the whole assertion.
    }

    @Test
    void validateRejectsWhatCanonicalizeRejects() {
        assertThrows(SchemaValidationException.class,
                () -> CanonicalIdentity.validate("registry-test.tn"));
    }

    @Test
    void sameIdentityIgnoresSchemeAndPin() {
        // Neither the scheme nor a ?sha256= pin (verification metadata, not identity) distinguishes a
        // reference -- the whole reason references are compared canonically rather than as raw strings.
        assertTrue(CanonicalIdentity.sameIdentity(
                "https://example.test/registry-test.tn?sha256=" + "a".repeat(64),
                "http://example.test/registry-test.tn"));
    }

    @Test
    void sameIdentitySeparatesGenuinelyDifferentPaths() {
        assertFalse(CanonicalIdentity.sameIdentity(
                "https://example.test/one.tn", "https://example.test/two.tn"));
    }

    @Test
    void sameIdentityRejectsAnInvalidCandidateRatherThanReportingUnequal() {
        assertThrows(SchemaValidationException.class,
                () -> CanonicalIdentity.sameIdentity("https://example.test/one.tn", "one.tn"));
    }
}
