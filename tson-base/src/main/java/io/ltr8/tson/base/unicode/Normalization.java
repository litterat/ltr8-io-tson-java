package io.ltr8.tson.base.unicode;

import java.text.Normalizer;

/**
 * The meta-kernel's {@code normalization}: the form a text family puts its decoded value into
 * (SPEC-FEEDBACK.md #19). A token is unquoted and unescaped, then put into this form, and the result is the value
 * -- the one every facet judges and every identity compares, as {@code 0x10} decodes to 16. A written value is
 * never refused for not being in the form.
 */
public enum Normalization {
    /** The value is the text as written. */
    NONE,
    /** NFC, the series' own form ([TSON-DATA] §2.5, §2.6). */
    NFC,
    /** NFKC: compatibility variants fold to their ordinary forms -- {@code ﬁ} to {@code fi}, full-width to ASCII. */
    NFKC,
    /**
     * {@code toNFKC_Casefold}, UAX #31 §5's form for names compared without case: NFKC, a full case fold, and the
     * default ignorables removed. Over ASCII it is lowercasing.
     */
    NFKC_CASEFOLD;

    /** {@code text} in this form, returning it unchanged when it already is. */
    public String apply(String text) {
        return switch (this) {
            case NONE -> text;
            case NFC -> Nfc.of(text);
            case NFKC -> Normalizer.isNormalized(text, Normalizer.Form.NFKC)
                    ? text
                    : Normalizer.normalize(text, Normalizer.Form.NFKC);
            case NFKC_CASEFOLD -> NfkcCasefold.apply(text);
        };
    }

    /** Whether {@code text} is already in this form. */
    public boolean holds(String text) {
        return switch (this) {
            case NONE -> true;
            case NFC -> Normalizer.isNormalized(text, Normalizer.Form.NFC);
            case NFKC -> Normalizer.isNormalized(text, Normalizer.Form.NFKC);
            case NFKC_CASEFOLD -> NfkcCasefold.isFolded(text);
        };
    }
}
