package io.ltr8.unicode;

import java.text.Normalizer;

/**
 * Normalization Form C (UAX #15): {@link #of} puts a string into it, returning the string itself when it already
 * is, so a caller that normalises on every comparison pays only for text that needs it.
 *
 * <p><b>The common case allocates nothing.</b> Almost every name is already normalised, and most are ASCII.
 * {@link Normalizer#isNormalized} allocates working buffers whatever its input, so it is reached only for a
 * string holding a character at or above U+0300, where the combining marks begin: every character below it has
 * canonical combining class 0 and composes with no other character below it, so a string of them is NFC by
 * construction ({@code NfcTest} checks every such character and pair). Only a string that is not already NFC
 * pays for {@link Normalizer#normalize}.
 */
public final class Nfc {

    /** The first character that can take part in NFC's reordering or composition; see the class comment. */
    private static final char FIRST_COMBINING = '\u0300';

    private Nfc() {
    }

    /** {@code text} in NFC, returning it unchanged when it already is. */
    public static String of(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) >= FIRST_COMBINING) {
                return Normalizer.isNormalized(text, Normalizer.Form.NFC)
                        ? text
                        : Normalizer.normalize(text, Normalizer.Form.NFC);
            }
        }
        return text;
    }
}
