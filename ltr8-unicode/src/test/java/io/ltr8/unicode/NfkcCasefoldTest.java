package io.ltr8.unicode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spot checks of {@code toNFKC_Casefold}, one per way the derivation can go wrong. The whole property is checked
 * against {@code DerivedNormalizationProps.txt} offline, as {@link NfkcCasefold}'s Javadoc records.
 */
class NfkcCasefoldTest {

    @Test
    void asciiLowercases() {
        assertEquals("content-type", NfkcCasefold.apply("Content-Type"));
    }

    @Test
    void foldedAsciiComesBackAsItIs() {
        String name = "idempotency-key";
        assertSame(name, NfkcCasefold.apply(name));
    }

    @Test
    void compatibilityCharactersFoldToTheirAsciiForms() {
        assertEquals("content", NfkcCasefold.apply("Ｃｏｎｔｅｎｔ"));
        assertEquals("file", NfkcCasefold.apply("ﬁle"));
        assertEquals("k", NfkcCasefold.apply("K"));
    }

    @Test
    void sharpSFoldsToTwoLettersAndTheCapitalNeedsASecondPass() {
        assertEquals("strasse", NfkcCasefold.apply("straße"));
        assertEquals("ss", NfkcCasefold.apply("ẞ"));
    }

    @Test
    void dotlessIFoldsToItself() {
        assertEquals("ı", NfkcCasefold.apply("ı"));
        assertEquals("i̇", NfkcCasefold.apply("İ"));
    }

    @Test
    void cherokeeFoldsToUppercase() {
        assertEquals("Ꭰ", NfkcCasefold.apply("ꭰ"));
        assertEquals("Ᏸ", NfkcCasefold.apply("ᏸ"));
        assertEquals("Ꭰ", NfkcCasefold.apply("Ꭰ"));
    }

    @Test
    void finalSigmaAndMicroFoldToTheirOrdinaryForms() {
        assertEquals("σ", NfkcCasefold.apply("ς"));
        assertEquals("μ", NfkcCasefold.apply("µ"));
    }

    @Test
    void defaultIgnorablesAreRemoved() {
        assertEquals("ab", NfkcCasefold.apply("a‍b"));
        assertEquals("ab", NfkcCasefold.apply("a­b"));
        assertTrue(NfkcCasefold.isDefaultIgnorable(0xe0001));
        assertFalse(NfkcCasefold.isDefaultIgnorable('a'));
    }

    @Test
    void theResultIsNfc() {
        assertEquals("café", NfkcCasefold.apply("CAFÉ"));
    }

    @Test
    void isFoldedTellsAFoldedTextFromOneThatIsNot() {
        assertTrue(NfkcCasefold.isFolded("straße".replace("ß", "ss")));
        assertFalse(NfkcCasefold.isFolded("Straße"));
    }
}
