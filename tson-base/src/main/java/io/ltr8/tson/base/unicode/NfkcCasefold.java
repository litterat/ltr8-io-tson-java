package io.ltr8.tson.base.unicode;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Unicode's {@code toNFKC_Casefold} (The Unicode Standard §3.13; UAX #31 §5's form for identifiers compared
 * without case): each character mapped to its {@code NFKC_Casefold} value, then the whole text put into NFC.
 *
 * <p><b>The per-character value is derived from the JDK's own data</b> rather than carried as a table: NFKD,
 * then a full case fold, then the default ignorable code points removed, then NFKC, repeated until the text
 * stops changing. A fold that settles takes at most two changing passes -- {@code ẞ} lowercases to {@code ß},
 * which only the second pass folds to {@code ss}. The full case fold is {@code toUpperCase} then
 * {@code toLowerCase} under {@link Locale#ROOT}, which differs from {@code CaseFolding.txt}'s C and F mappings
 * in exactly three places, each handled here: dotless {@code ı} folds to itself, the Cherokee letters fold to
 * their uppercase forms, and U+13F8..13FD fold onto U+13F0..13F5.
 *
 * <p>Verified against {@code DerivedNormalizationProps.txt}'s {@code NFKC_CF} property for
 * {@link Xid#UNICODE_VERSION} over all 1,112,064 non-surrogate code points: no difference. The
 * {@link #DEFAULT_IGNORABLE} ranges are {@code DerivedCoreProperties.txt}'s {@code Default_Ignorable_Code_Point}
 * for the same version. A JDK whose Unicode version moves needs both checks rerun.
 */
public final class NfkcCasefold {

    private NfkcCasefold() {
    }

    /** {@code toNFKC_Casefold(text)}. ASCII text is lowercased in place of the general path. */
    public static String apply(String text) {
        int n = text.length();
        boolean ascii = true;
        boolean upper = false;
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c >= 0x80) {
                ascii = false;
                break;
            }
            upper |= c >= 'A' && c <= 'Z';
        }
        if (ascii) {
            return upper ? text.toLowerCase(Locale.ROOT) : text;
        }
        StringBuilder mapped = new StringBuilder(n);
        text.codePoints().forEach(cp -> mapped.append(map(cp)));
        return Normalizer.normalize(mapped, Normalizer.Form.NFC);
    }

    /** Whether {@code text} is already its own {@code toNFKC_Casefold}. */
    public static boolean isFolded(String text) {
        return apply(text).equals(text);
    }

    /** One code point's {@code NFKC_Casefold} value. */
    private static String map(int cp) {
        String s = Character.toString(cp);
        while (true) {
            String next = Normalizer.normalize(
                    withoutIgnorables(fold(Normalizer.normalize(s, Normalizer.Form.NFKD))), Normalizer.Form.NFKC);
            if (next.equals(s)) {
                return s;
            }
            s = next;
        }
    }

    private static String fold(String s) {
        StringBuilder out = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            if (cp == 0x0131 || (cp >= 0x13A0 && cp <= 0x13F5)) {
                out.appendCodePoint(cp);
            } else if (cp >= 0x13F8 && cp <= 0x13FD) {
                out.appendCodePoint(cp - 8);
            } else if (cp >= 0xAB70 && cp <= 0xABBF) {
                out.appendCodePoint(cp - 0xAB70 + 0x13A0);
            } else {
                out.append(Character.toString(cp).toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT));
            }
        });
        return out.toString();
    }

    private static String withoutIgnorables(String s) {
        StringBuilder out = new StringBuilder(s.length());
        s.codePoints().filter(cp -> !isDefaultIgnorable(cp)).forEach(out::appendCodePoint);
        return out.toString();
    }

    /** {@code Default_Ignorable_Code_Point}, which {@code NFKC_Casefold} removes. */
    static boolean isDefaultIgnorable(int cp) {
        for (int i = 0; i < DEFAULT_IGNORABLE.length; i += 2) {
            if (cp < DEFAULT_IGNORABLE[i]) {
                return false;
            }
            if (cp <= DEFAULT_IGNORABLE[i + 1]) {
                return true;
            }
        }
        return false;
    }

    /** Inclusive ranges, ascending. */
    private static final int[] DEFAULT_IGNORABLE = {
            0x00AD, 0x00AD, 0x034F, 0x034F, 0x061C, 0x061C, 0x115F, 0x1160, 0x17B4, 0x17B5, 0x180B, 0x180F,
            0x200B, 0x200F, 0x202A, 0x202E, 0x2060, 0x206F, 0x3164, 0x3164, 0xFE00, 0xFE0F, 0xFEFF, 0xFEFF,
            0xFFA0, 0xFFA0, 0xFFF0, 0xFFF8, 0x1BCA0, 0x1BCA3, 0x1D173, 0x1D17A, 0xE0000, 0xE0FFF,
    };
}
