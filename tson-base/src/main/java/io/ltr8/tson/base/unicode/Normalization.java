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
    NFKC_CASEFOLD,
    /**
     * The text as written with U+0041..005A mapped to U+0061..007A and nothing else: the comparison the
     * case-insensitive ASCII naming systems state -- RFC 9110 field names, RFC 3986 §3.1 schemes, RFC 4343 DNS
     * names. A compatibility character is left as it is, so a profile of ASCII letters still refuses a full-width
     * or Kelvin-sign spelling that {@link #NFKC_CASEFOLD} would fold into it. No Unicode normalization runs: a
     * composed and a decomposed {@code É} stay two values, as under {@link #NONE}.
     */
    ASCII_CASEFOLD;

    /** {@code text} in this form, returning it unchanged when it already is. */
    public String apply(String text) {
        return switch (this) {
            case NONE -> text;
            case NFC -> Nfc.of(text);
            case NFKC -> Normalizer.isNormalized(text, Normalizer.Form.NFKC)
                    ? text
                    : Normalizer.normalize(text, Normalizer.Form.NFKC);
            case NFKC_CASEFOLD -> NfkcCasefold.apply(text);
            case ASCII_CASEFOLD -> asciiLowercase(text);
        };
    }

    /** Whether {@code text} is already in this form. */
    public boolean holds(String text) {
        return switch (this) {
            case NONE -> true;
            case NFC -> Normalizer.isNormalized(text, Normalizer.Form.NFC);
            case NFKC -> Normalizer.isNormalized(text, Normalizer.Form.NFKC);
            case NFKC_CASEFOLD -> NfkcCasefold.isFolded(text);
            case ASCII_CASEFOLD -> asciiLowercase(text) == text;
        };
    }

    /** {@code text} with A..Z lowercased, the same instance when it has none. */
    private static String asciiLowercase(String text) {
        int n = text.length();
        int i = 0;
        while (i < n && (text.charAt(i) < 'A' || text.charAt(i) > 'Z')) {
            i++;
        }
        if (i == n) {
            return text;
        }
        char[] chars = text.toCharArray();
        for (; i < n; i++) {
            if (chars[i] >= 'A' && chars[i] <= 'Z') {
                chars[i] += 'a' - 'A';
            }
        }
        return new String(chars);
    }
}
