package io.ltr8.unicode;

import java.util.Optional;

/**
 * RFC 5893's Bidi rule for IDNA2008: the six conditions a label of a domain name containing right-to-left text
 * must meet, so that the name displays in one order however the labels around it run. Bidi classes are the
 * Unicode Bidi_Class property at the JDK's Unicode version ({@link Character#getDirectionality}).
 *
 * <p>The rule applies to <em>every</em> label of a bidi domain name -- one where any label holds an R, AL or AN
 * character ({@link #hasRightToLeft}) -- including its ASCII labels, so {@code 1abc} is refused beside an Arabic
 * label, its first character being EN rather than L. A caller judges the whole name first and applies
 * {@link #violation} to each label only when the name is a bidi one.
 */
public final class BidiRule {

    private BidiRule() {
    }

    /** Whether {@code label} holds a character of class R, AL or AN, which makes its name a bidi domain name. */
    public static boolean hasRightToLeft(String label) {
        for (int i = 0; i < label.length(); ) {
            int cp = label.codePointAt(i);
            byte d = Character.getDirectionality(cp);
            if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
                    || d == Character.DIRECTIONALITY_ARABIC_NUMBER) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    /** RFC 5893 §2's six conditions over one non-empty label of a bidi domain name: the violation, or empty. */
    public static Optional<String> violation(String label) {
        byte first = Character.getDirectionality(label.codePointAt(0));
        boolean rtl;
        if (first == Character.DIRECTIONALITY_RIGHT_TO_LEFT
                || first == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
            rtl = true;
        } else if (first == Character.DIRECTIONALITY_LEFT_TO_RIGHT) {
            rtl = false;
        } else {
            return Optional.of("its first character is neither left-to-right nor right-to-left, which a label of "
                    + "a name holding right-to-left text must begin with (RFC 5893 §2 rule 1)");
        }
        boolean europeanNumber = false;
        boolean arabicNumber = false;
        byte lastNonMark = first;
        for (int i = 0; i < label.length(); ) {
            int cp = label.codePointAt(i);
            byte d = Character.getDirectionality(cp);
            if (!(rtl ? allowedRightToLeft(d) : allowedLeftToRight(d))) {
                return Optional.of("U+%04X has a bidi class a %s label cannot hold (RFC 5893 §2 rule %d)"
                        .formatted(cp, rtl ? "right-to-left" : "left-to-right", rtl ? 2 : 5));
            }
            europeanNumber |= d == Character.DIRECTIONALITY_EUROPEAN_NUMBER;
            arabicNumber |= d == Character.DIRECTIONALITY_ARABIC_NUMBER;
            if (d != Character.DIRECTIONALITY_NONSPACING_MARK) {
                lastNonMark = d;
            }
            i += Character.charCount(cp);
        }
        if (rtl) {
            if (!(lastNonMark == Character.DIRECTIONALITY_RIGHT_TO_LEFT
                    || lastNonMark == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
                    || lastNonMark == Character.DIRECTIONALITY_EUROPEAN_NUMBER
                    || lastNonMark == Character.DIRECTIONALITY_ARABIC_NUMBER)) {
                return Optional.of("a right-to-left label must end in a right-to-left character or a digit "
                        + "(RFC 5893 §2 rule 3)");
            }
            if (europeanNumber && arabicNumber) {
                return Optional.of("a right-to-left label cannot mix European and Arabic-Indic digits "
                        + "(RFC 5893 §2 rule 4)");
            }
        } else if (!(lastNonMark == Character.DIRECTIONALITY_LEFT_TO_RIGHT
                || lastNonMark == Character.DIRECTIONALITY_EUROPEAN_NUMBER)) {
            return Optional.of("a left-to-right label must end in a left-to-right character or a digit "
                    + "(RFC 5893 §2 rule 6)");
        }
        return Optional.empty();
    }

    /** Rule 2: R, AL, AN, EN, ES, CS, ET, ON, BN and NSM. */
    private static boolean allowedRightToLeft(byte d) {
        return d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
                || d == Character.DIRECTIONALITY_ARABIC_NUMBER || neutralOrNumber(d);
    }

    /** Rule 5: L, EN, ES, CS, ET, ON, BN and NSM. */
    private static boolean allowedLeftToRight(byte d) {
        return d == Character.DIRECTIONALITY_LEFT_TO_RIGHT || neutralOrNumber(d);
    }

    private static boolean neutralOrNumber(byte d) {
        return d == Character.DIRECTIONALITY_EUROPEAN_NUMBER || d == Character.DIRECTIONALITY_EUROPEAN_NUMBER_SEPARATOR
                || d == Character.DIRECTIONALITY_COMMON_NUMBER_SEPARATOR
                || d == Character.DIRECTIONALITY_EUROPEAN_NUMBER_TERMINATOR
                || d == Character.DIRECTIONALITY_OTHER_NEUTRALS || d == Character.DIRECTIONALITY_BOUNDARY_NEUTRAL
                || d == Character.DIRECTIONALITY_NONSPACING_MARK;
    }
}
