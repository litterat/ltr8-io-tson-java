package io.ltr8.unicode;

import java.text.Normalizer;

/**
 * RFC 5892's derived property for IDNA2008: whether a code point may stand in a U-label, computed by the RFC's
 * own derivation (§3) from Unicode properties at {@link Xid#UNICODE_VERSION}, rather than read from a table.
 *
 * <p><b>Computed, because there is no table to read.</b> IANA published the derived values per Unicode version up
 * to Unicode 12.0 and no further; RFC 5892 §5.1 defines the property as the rules applied to a version's data, so
 * the rules are the authority and a table is a cache of them. {@code IdnaPropertyTest} checks this against IANA's
 * 12.0 table for every code point that version assigns.
 *
 * <p><b>Built so a valid code point stays valid.</b> The derivation reads properties Unicode keeps stable --
 * General_Category for letters and digits, normalization, case folding -- and RFC 5892 §2.7's
 * BackwardCompatible category exists to pin a value should one move. A newly assigned code point can become
 * PVALID in a later version; one that is PVALID does not stop being so. That is what lets a content-addressed
 * name rest on it.
 *
 * <p>{@link #of} costs one normalization of the code point beyond ASCII and nothing for ASCII, so a caller
 * judging a name pays only for the characters that need it.
 */
public enum IdnaProperty {

    /** Protocol-valid: permitted in a U-label anywhere. */
    PVALID,

    /** A join control, permitted only where RFC 5892 Appendix A's rule for it holds ({@link #contextual}). */
    CONTEXTJ,

    /** Permitted only where RFC 5892 Appendix A's rule for it holds ({@link #contextual}). */
    CONTEXTO,

    /** Never permitted in a U-label. */
    DISALLOWED,

    /** Not assigned at this Unicode version, so not permitted until a version assigns it. */
    UNASSIGNED;

    /** RFC 5892 §3's derivation, in its order: the first category {@code cp} falls in decides. */
    public static IdnaProperty of(int cp) {
        if (cp < 0x80) {
            return isLdh(cp) ? PVALID : DISALLOWED;
        }
        IdnaProperty exception = exception(cp);
        if (exception != null) {
            return exception;
        }
        if (isUnassigned(cp)) {
            return UNASSIGNED;
        }
        if (cp == Xid.ZWNJ || cp == Xid.ZWJ) {
            return CONTEXTJ;
        }
        if (isUnstable(cp) || isIgnorableProperty(cp) || isIgnorableBlock(cp) || isOldHangulJamo(cp)) {
            return DISALLOWED;
        }
        return isLetterDigit(cp) ? PVALID : DISALLOWED;
    }

    /**
     * RFC 5892 Appendix A: whether the CONTEXTJ or CONTEXTO code point at {@code index} in {@code label} stands
     * where its rule permits it. {@code label} is one label, in NFC. A code point with no rule -- one whose property
     * is neither contextual value -- is never permitted by this.
     */
    public static boolean contextual(String label, int index) {
        int cp = label.codePointAt(index);
        return switch (cp) {
            case 0x200C -> afterVirama(label, index) || joinsAcross(label, index);
            case 0x200D -> afterVirama(label, index);
            case 0x00B7 -> index > 0 && label.charAt(index - 1) == 'l'
                    && index + 1 < label.length() && label.charAt(index + 1) == 'l';
            case 0x0375 -> index + 1 < label.length()
                    && Character.UnicodeScript.of(label.codePointAt(index + 1)) == Character.UnicodeScript.GREEK;
            case 0x05F3, 0x05F4 -> index > 0
                    && Character.UnicodeScript.of(label.codePointBefore(index)) == Character.UnicodeScript.HEBREW;
            case 0x30FB -> hasKanaOrHan(label);
            default -> {
                if (cp >= 0x0660 && cp <= 0x0669) {
                    yield !hasAny(label, 0x06F0, 0x06F9);
                }
                if (cp >= 0x06F0 && cp <= 0x06F9) {
                    yield !hasAny(label, 0x0660, 0x0669);
                }
                yield false;
            }
        };
    }

    /** §2.6's Exceptions, which override every derived category. */
    private static IdnaProperty exception(int cp) {
        return switch (cp) {
            case 0x00DF, 0x03C2, 0x06FD, 0x06FE, 0x0F0B, 0x3007 -> PVALID;
            case 0x00B7, 0x0375, 0x05F3, 0x05F4, 0x30FB -> CONTEXTO;
            case 0x0640, 0x07FA, 0x302E, 0x302F, 0x3031, 0x3032, 0x3033, 0x3034, 0x3035, 0x303B -> DISALLOWED;
            default -> (cp >= 0x0660 && cp <= 0x0669) || (cp >= 0x06F0 && cp <= 0x06F9) ? CONTEXTO : null;
        };
    }

    /** §2.5's LDH: the letters, digits and hyphen a host name has always been written in. */
    private static boolean isLdh(int cp) {
        return (cp >= 'a' && cp <= 'z') || (cp >= '0' && cp <= '9') || cp == '-';
    }

    /** §2.8's Unassigned: General_Category Cn and not a noncharacter. */
    private static boolean isUnassigned(int cp) {
        return Character.getType(cp) == Character.UNASSIGNED && !isNoncharacter(cp);
    }

    /**
     * §2.2's Unstable: a code point that NFKC, a case fold and NFKC again would change. The fold is
     * {@code CaseFolding.txt}'s full fold, as {@link NfkcCasefold} computes it.
     */
    private static boolean isUnstable(int cp) {
        String s = Character.toString(cp);
        String nfkc = Normalizer.normalize(s, Normalizer.Form.NFKC);
        return !Normalizer.normalize(NfkcCasefold.fold(nfkc), Normalizer.Form.NFKC).equals(s);
    }

    /** §2.3's IgnorableProperties: Default_Ignorable_Code_Point, White_Space or Noncharacter_Code_Point. */
    private static boolean isIgnorableProperty(int cp) {
        return NfkcCasefold.isDefaultIgnorable(cp) || isWhiteSpace(cp) || isNoncharacter(cp);
    }

    /** {@code PropList.txt}'s White_Space, a fixed set. */
    private static boolean isWhiteSpace(int cp) {
        return (cp >= 0x0009 && cp <= 0x000D) || cp == 0x0020 || cp == 0x0085 || cp == 0x00A0 || cp == 0x1680
                || (cp >= 0x2000 && cp <= 0x200A) || cp == 0x2028 || cp == 0x2029 || cp == 0x202F || cp == 0x205F
                || cp == 0x3000;
    }

    /** Noncharacter_Code_Point: U+FDD0..FDEF and the last two code points of every plane. */
    private static boolean isNoncharacter(int cp) {
        return (cp >= 0xFDD0 && cp <= 0xFDEF) || (cp & 0xFFFE) == 0xFFFE;
    }

    /**
     * §2.4's IgnorableBlocks: Combining Diacritical Marks for Symbols, Musical Symbols and Ancient Greek Musical
     * Notation.
     */
    private static boolean isIgnorableBlock(int cp) {
        return (cp >= 0x20D0 && cp <= 0x20FF) || (cp >= 0x1D100 && cp <= 0x1D1FF) || (cp >= 0x1D200 && cp <= 0x1D24F);
    }

    /** §2.9's OldHangulJamo: Hangul_Syllable_Type L, V or T, the conjoining jamo. */
    private static boolean isOldHangulJamo(int cp) {
        return (cp >= 0x1100 && cp <= 0x11FF) || (cp >= 0xA960 && cp <= 0xA97C) || (cp >= 0xD7B0 && cp <= 0xD7C6)
                || (cp >= 0xD7CB && cp <= 0xD7FB);
    }

    /** §2.1's LetterDigits: General_Category Ll, Lu, Lo, Nd, Lm, Mn or Mc. */
    private static boolean isLetterDigit(int cp) {
        return switch (Character.getType(cp)) {
            case Character.LOWERCASE_LETTER, Character.UPPERCASE_LETTER, Character.OTHER_LETTER,
                 Character.DECIMAL_DIGIT_NUMBER, Character.MODIFIER_LETTER, Character.NON_SPACING_MARK,
                 Character.COMBINING_SPACING_MARK -> true;
            default -> false;
        };
    }

    /** Appendix A.1 and A.2's first rule: the code point before is a virama. */
    private static boolean afterVirama(String label, int index) {
        return index > 0 && JoiningControls.isVirama(label.codePointBefore(index));
    }

    /**
     * Appendix A.1's second rule: ZWNJ between a left-joining and a right-joining character, with transparent ones
     * between -- {@code (Joining_Type:{L,D})(Joining_Type:T)* ZWNJ (Joining_Type:T)*(Joining_Type:{R,D})}.
     */
    private static boolean joinsAcross(String label, int index) {
        int i = index;
        int before;
        do {
            if (i == 0) {
                return false;
            }
            before = label.codePointBefore(i);
            i -= Character.charCount(before);
        } while (JoiningControls.isTransparent(before));
        if (!JoiningControls.isLeftJoining(before)) {
            return false;
        }
        int j = index + 1;
        while (j < label.length()) {
            int after = label.codePointAt(j);
            if (!JoiningControls.isTransparent(after)) {
                return JoiningControls.isRightJoining(after);
            }
            j += Character.charCount(after);
        }
        return false;
    }

    /** Appendix A.7: the label holds a Hiragana, Katakana or Han character. */
    private static boolean hasKanaOrHan(String label) {
        for (int i = 0; i < label.length(); ) {
            int cp = label.codePointAt(i);
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if (script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.HAN) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    private static boolean hasAny(String label, int from, int to) {
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if (c >= from && c <= to) {
                return true;
            }
        }
        return false;
    }
}
