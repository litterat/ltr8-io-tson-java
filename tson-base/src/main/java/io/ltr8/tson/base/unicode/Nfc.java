package io.ltr8.tson.base.unicode;

import java.text.Normalizer;

/**
 * NFC, applied where the series makes it identity rather than content.
 *
 * <p>[TSON-DATA] §7.2.1 requires it outright: "quoted tokens that occupy identifier positions -- record
 * field names, and any position a higher part designates as an identifier -- are NFC-normalised by the
 * resolver before identity comparison. String-typed positions are not normalised. Consequently,
 * {@code "café"} (decomposed) and {@code "café"} (precomposed) collide as duplicate field names, while two
 * string *values* with the same difference remain distinct strings." §2.5 and §2.6 state the same identity
 * from the other end.
 *
 * <p>So the axis is the <b>position</b>, not the quoting: a name normalises and a value does not. The lexer
 * cannot draw it, because at lex time a quoted token's position is not yet known -- what follows it decides
 * -- which is why §7.2.1 puts this above the lexer and why the first paragraph's "the lexer never alters
 * token text" holds unchanged. Applied here at the point the position <em>is</em> known: see {@code
 * TsonDataStream}'s {@code FieldName} emission and the map readers' key comparison.
 *
 * <p>That is one tier earlier than the "resolver layer" §7.2.1 names, which satisfies its requirement
 * (normalisation still precedes every identity comparison) and makes the Tier 3 AST carry the normalised
 * name, so a re-emitted document spells a decomposed field name composed.
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
