package io.ltr8.tson.schema;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TsonCanonicalIdentityTest {

    @Test
    void stripsSchemeAndDelimiterFromTheRealMetaKernelId() {
        Assertions.assertEquals("tson.io/2026/34/m/meta-kernel.tn",
                TsonCanonicalIdentity.canonicalize("https://tson.io/2026/34/m/meta-kernel.tn"));
    }

    @Test
    void httpAndHttpsResolveToTheSameIdentity() {
        assertEquals(TsonCanonicalIdentity.canonicalize("https://tson.io/2026/34/m/meta-kernel.tn"),
                TsonCanonicalIdentity.canonicalize("http://tson.io/2026/34/m/meta-kernel.tn"));
    }

    @Test
    void queryIsDropped() {
        assertEquals("tson.io/2026/34/m/meta-kernel.tn",
                TsonCanonicalIdentity.canonicalize("https://tson.io/2026/34/m/meta-kernel.tn?sha256=abc123"));
    }

    @Test
    void rejectsNonLowercaseHost() {
        assertThrows(TsonSchemaValidationException.class,
                () -> TsonCanonicalIdentity.canonicalize("https://Tson.io/2026/34/m/meta-kernel.tn"));
    }

    @Test
    void rejectsDotSegmentInPath() {
        assertThrows(TsonSchemaValidationException.class,
                () -> TsonCanonicalIdentity.canonicalize("https://tson.io/2026/../m/meta-kernel.tn"));
    }

    @Test
    void rejectsUserinfo() {
        assertThrows(TsonSchemaValidationException.class,
                () -> TsonCanonicalIdentity.canonicalize("https://user@tson.io/2026/34/m/meta-kernel.tn"));
    }

    @Test
    void rejectsExplicitPort() {
        assertThrows(TsonSchemaValidationException.class,
                () -> TsonCanonicalIdentity.canonicalize("https://tson.io:443/2026/34/m/meta-kernel.tn"));
    }

    @Test
    void rejectsFragment() {
        assertThrows(TsonSchemaValidationException.class,
                () -> TsonCanonicalIdentity.canonicalize("https://tson.io/2026/34/m/meta-kernel.tn#section"));
    }

    @Test
    void rejectsPercentEncodedUnreservedCharacter() {
        // %7E decodes to '~', an unreserved character -- MUST NOT be percent-encoded.
        assertThrows(TsonSchemaValidationException.class,
                () -> TsonCanonicalIdentity.canonicalize("https://tson.io/2026/34/m/meta-kernel%7E.tn"));
    }

    @Test
    void allowsPercentEncodingOfAReservedCharacter() {
        // %2F decodes to '/', a reserved character -- percent-encoding it is not forbidden.
        assertEquals("tson.io/2026%2F32/m/meta-kernel.tn",
                TsonCanonicalIdentity.canonicalize("https://tson.io/2026%2F32/m/meta-kernel.tn"));
    }

    @Test
    void rejectsMissingScheme() {
        assertThrows(TsonSchemaValidationException.class,
                () -> TsonCanonicalIdentity.canonicalize("tson.io/2026/34/m/meta-kernel.tn"));
    }

    @Test
    void rejectsMissingHost() {
        assertThrows(TsonSchemaValidationException.class,
                () -> TsonCanonicalIdentity.canonicalize("mailto:someone@example.com"));
    }

    @Test
    void rejectsAUriCarryingAPort() {
        assertThrows(TsonSchemaValidationException.class,
                () -> TsonCanonicalIdentity.canonicalize("https://example.test:8080/registry-test.tn"));
    }

    @Test
    void validateAcceptsAWellFormedCandidateSilently() {
        TsonCanonicalIdentity.validate("https://example.test/registry-test.tn");
        // No exception -- that's the whole assertion.
    }

    @Test
    void validateRejectsWhatCanonicalizeRejects() {
        assertThrows(TsonSchemaValidationException.class,
                () -> TsonCanonicalIdentity.validate("registry-test.tn"));
    }

    @Test
    void sameIdentityIgnoresSchemeAndPin() {
        // Neither the scheme nor a ?sha256= pin (verification metadata, not identity) distinguishes a
        // reference -- the whole reason references are compared canonically rather than as raw strings.
        assertTrue(TsonCanonicalIdentity.sameIdentity(
                "https://example.test/registry-test.tn?sha256=" + "a".repeat(64),
                "http://example.test/registry-test.tn"));
    }

    @Test
    void sameIdentitySeparatesGenuinelyDifferentPaths() {
        assertFalse(TsonCanonicalIdentity.sameIdentity(
                "https://example.test/one.tn", "https://example.test/two.tn"));
    }

    @Test
    void sameIdentityRejectsAnInvalidCandidateRatherThanReportingUnequal() {
        assertThrows(TsonSchemaValidationException.class,
                () -> TsonCanonicalIdentity.sameIdentity("https://example.test/one.tn", "one.tn"));
    }
}
