package io.ltr8.unicode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link Normalization#ASCII_CASEFOLD}: A..Z lowercased, and no other character touched. */
class NormalizationTest {

    @Test
    void asciiCasefoldLowercasesAsciiLetters() {
        assertEquals("content-type", Normalization.ASCII_CASEFOLD.apply("Content-Type"));
        assertEquals("cafÉ", Normalization.ASCII_CASEFOLD.apply("CAFÉ"));
    }

    @Test
    void asciiCasefoldReturnsAFoldedTextAsItIs() {
        String name = "idempotency-key";
        assertSame(name, Normalization.ASCII_CASEFOLD.apply(name));
        assertTrue(Normalization.ASCII_CASEFOLD.holds(name));
        assertFalse(Normalization.ASCII_CASEFOLD.holds("Idempotency-Key"));
    }

    @Test
    void asciiCasefoldLeavesCompatibilityCharactersAlone() {
        assertEquals("Ｃontent", Normalization.ASCII_CASEFOLD.apply("Ｃontent"));
        assertEquals("Keep", Normalization.ASCII_CASEFOLD.apply("Keep"));
        assertEquals("preſent", Normalization.ASCII_CASEFOLD.apply("preſent"));
        assertEquals("ab­c", Normalization.ASCII_CASEFOLD.apply("AB­C"));
    }

    @Test
    void asciiCasefoldDoesNotNormalize() {
        assertEquals("é", Normalization.ASCII_CASEFOLD.apply("É"));
    }
}
