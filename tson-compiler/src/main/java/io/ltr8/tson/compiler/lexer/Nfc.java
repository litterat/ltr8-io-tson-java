package io.ltr8.tson.compiler.lexer;

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
 * <p>The common case allocates nothing: almost every name is already normalised -- an unquoted one always
 * is, the lexer having rejected it otherwise -- so the check short-circuits before {@link
 * Normalizer#normalize} would build a second string.
 */
public final class Nfc {

    private Nfc() {
    }

    /** {@code text} in NFC, returning it unchanged when it already is. */
    public static String of(String text) {
        return Normalizer.isNormalized(text, Normalizer.Form.NFC)
                ? text
                : Normalizer.normalize(text, Normalizer.Form.NFC);
    }
}
