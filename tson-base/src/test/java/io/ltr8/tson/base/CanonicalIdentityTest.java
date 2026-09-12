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
        Assertions.assertEquals("tson.io/2026/36/m/meta-kernel.tn",
                CanonicalIdentity.canonicalize("https://tson.io/2026/36/m/meta-kernel.tn"));
    }

    @Test
    void httpAndHttpsResolveToTheSameIdentity() {
        assertEquals(CanonicalIdentity.canonicalize("https://tson.io/2026/36/m/meta-kernel.tn"),
                CanonicalIdentity.canonicalize("http://tson.io/2026/36/m/meta-kernel.tn"));
    }

    @Test
    void queryIsDropped() {
        assertEquals("tson.io/2026/36/m/meta-kernel.tn",
                CanonicalIdentity.canonicalize("https://tson.io/2026/36/m/meta-kernel.tn?sha256=abc123"));
    }

    @Test
    void rejectsNonLowercaseHost() {
        assertThrows(SchemaValidationException.class,
                () -> CanonicalIdentity.canonicalize("https://Tson.io/2026/36/m/meta-kernel.tn"));
    }

    @Test
    void rejectsDotSegmentInPath() {
        assertThrows(SchemaValidationException.class,
                () -> CanonicalIdentity.canonicalize("https://tson.io/2026/../m/meta-kernel.tn"));
    }

    @Test
    void rejectsUserinfo() {
        assertThrows(SchemaValidationException.class,
                () -> CanonicalIdentity.canonicalize("https://user@tson.io/2026/36/m/meta-kernel.tn"));
    }

    @Test
    void rejectsExplicitPort() {
        assertThrows(SchemaValidationException.class,
                () -> CanonicalIdentity.canonicalize("https://tson.io:443/2026/36/m/meta-kernel.tn"));
    }

    @Test
    void rejectsFragment() {
        assertThrows(SchemaValidationException.class,
                () -> CanonicalIdentity.canonicalize("https://tson.io/2026/36/m/meta-kernel.tn#section"));
    }

    @Test
    void rejectsPercentEncodedUnreservedCharacter() {
        // %7E decodes to '~', an unreserved character -- MUST NOT be percent-encoded.
        assertThrows(SchemaValidationException.class,
                () -> CanonicalIdentity.canonicalize("https://tson.io/2026/36/m/meta-kernel%7E.tn"));
    }

    @Test
    void allowsPercentEncodingOfAReservedCharacter() {
        // %2F decodes to '/', a reserved character -- percent-encoding it is not forbidden.
        assertEquals("tson.io/2026%2F32/m/meta-kernel.tn",
                CanonicalIdentity.canonicalize("https://tson.io/2026%2F32/m/meta-kernel.tn"));
    }

    @Test
    void rejectsMissingScheme() {
        assertThrows(SchemaValidationException.class,
                () -> CanonicalIdentity.canonicalize("tson.io/2026/36/m/meta-kernel.tn"));
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
