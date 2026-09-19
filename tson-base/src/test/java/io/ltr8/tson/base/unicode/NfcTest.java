package io.ltr8.tson.base.unicode;

import org.junit.jupiter.api.Test;

import java.text.Normalizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NfcTest {

    /**
     * What the fast path stands on: below U+0300 every character, and every pair of characters, is already NFC
     * -- so no reordering (a nonzero combining class) and no composition can involve a string made only of them.
     */
    @Test
    void everyCharacterAndPairBelowTheCombiningMarksIsAlreadyNfc() {
        for (char first = 0; first < '\u0300'; first++) {
            assertTrue(Normalizer.isNormalized(String.valueOf(first), Normalizer.Form.NFC),
                    "U+" + Integer.toHexString(first));
            for (char second = 0; second < '\u0300'; second++) {
                String pair = new String(new char[] {first, second});
                if (!Normalizer.isNormalized(pair, Normalizer.Form.NFC)) {
                    throw new AssertionError("U+" + Integer.toHexString(first) + " U+"
                            + Integer.toHexString(second) + " is not NFC");
                }
            }
        }
    }

    @Test
    void aNameBelowTheCombiningMarksComesBackAsItIs() {
        String name = "caf\u00e9_total";
        assertSame(name, Nfc.of(name));
    }

    @Test
    void aDecomposedNameComposes() {
        assertEquals("caf\u00e9", Nfc.of("cafe\u0301"));
    }

    @Test
    void anNfcNameAboveTheCombiningMarksComesBackAsItIs() {
        String name = "\u03b1\u03b2\u03b3";
        assertSame(name, Nfc.of(name));
    }
}
