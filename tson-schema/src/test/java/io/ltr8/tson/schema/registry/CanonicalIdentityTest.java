package io.ltr8.tson.schema.registry;

import io.ltr8.tson.schema.SchemaValidationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CanonicalIdentityTest {

    @Test
    void stripsSchemeAndDelimiterFromTheRealMetaKernelId() {
        assertEquals("tson.io/2026/32/m/meta-kernel.tn1",
                CanonicalIdentity.of("https://tson.io/2026/32/m/meta-kernel.tn1"));
    }

    @Test
    void httpAndHttpsResolveToTheSameIdentity() {
        assertEquals(CanonicalIdentity.of("https://tson.io/2026/32/m/meta-kernel.tn1"),
                CanonicalIdentity.of("http://tson.io/2026/32/m/meta-kernel.tn1"));
    }

    @Test
    void queryIsDropped() {
        assertEquals("tson.io/2026/32/m/meta-kernel.tn1",
                CanonicalIdentity.of("https://tson.io/2026/32/m/meta-kernel.tn1?sha256=abc123"));
    }

    @Test
    void rejectsNonLowercaseHost() {
        assertThrows(SchemaValidationException.class,
                () -> CanonicalIdentity.of("https://Tson.io/2026/32/m/meta-kernel.tn1"));
    }

    @Test
    void rejectsDotSegmentInPath() {
        assertThrows(SchemaValidationException.class,
                () -> CanonicalIdentity.of("https://tson.io/2026/../m/meta-kernel.tn1"));
    }

    @Test
    void rejectsUserinfo() {
        assertThrows(SchemaValidationException.class,
                () -> CanonicalIdentity.of("https://user@tson.io/2026/32/m/meta-kernel.tn1"));
    }

    @Test
    void rejectsExplicitPort() {
        assertThrows(SchemaValidationException.class,
                () -> CanonicalIdentity.of("https://tson.io:443/2026/32/m/meta-kernel.tn1"));
    }

    @Test
    void rejectsFragment() {
        assertThrows(SchemaValidationException.class,
                () -> CanonicalIdentity.of("https://tson.io/2026/32/m/meta-kernel.tn1#section"));
    }

    @Test
    void rejectsPercentEncodedUnreservedCharacter() {
        // %7E decodes to '~', an unreserved character -- MUST NOT be percent-encoded.
        assertThrows(SchemaValidationException.class,
                () -> CanonicalIdentity.of("https://tson.io/2026/32/m/meta-kernel%7E.tn1"));
    }

    @Test
    void allowsPercentEncodingOfAReservedCharacter() {
        // %2F decodes to '/', a reserved character -- percent-encoding it is not forbidden.
        assertEquals("tson.io/2026%2F32/m/meta-kernel.tn1",
                CanonicalIdentity.of("https://tson.io/2026%2F32/m/meta-kernel.tn1"));
    }

    @Test
    void rejectsMissingScheme() {
        assertThrows(SchemaValidationException.class,
                () -> CanonicalIdentity.of("tson.io/2026/32/m/meta-kernel.tn1"));
    }

    @Test
    void rejectsMissingHost() {
        assertThrows(SchemaValidationException.class,
                () -> CanonicalIdentity.of("mailto:someone@example.com"));
    }
}
