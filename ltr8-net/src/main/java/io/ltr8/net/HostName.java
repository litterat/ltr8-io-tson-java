package io.ltr8.net;

import io.ltr8.unicode.BidiRule;
import io.ltr8.unicode.IdnaProperty;
import io.ltr8.unicode.Nfc;
import io.ltr8.unicode.Xid;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A domain name as a host names it: labels of letters, digits and hyphens (RFC 1123 §2.1), or internationalized
 * labels judged by IDNA2008 (RFC 5890-5893), written as U-labels or as their A-labels in any mix. The value is the
 * name, so every spelling of one name is one value: {@code Example.COM} and {@code example.com}, and
 * {@code xn--bcher-kva.example} and {@code bücher.example}.
 *
 * <p><b>Two forms of one value.</b> {@link #unicode()} is the name with each label as its U-label where it has one,
 * for display and an IRI; {@link #ascii()} is the name with each such label as its A-label, for DNS, a URI and
 * HTTP. Each is recovered from the other by Punycode alone, so neither is lost. {@link #toString()} is the U-label
 * form, the canonical text.
 *
 * <p><b>Validity is IDNA2008's, and nothing is mapped.</b> A U-label must already be in its one valid form -- NFC,
 * every code point {@code PVALID} or meeting its contextual rule ({@link IdnaProperty}), the name meeting RFC
 * 5893's Bidi rule -- and text that is not is refused with what to write instead, never rewritten into it. UTS
 * #46's mapping is what would rewrite it, and its tables move with each Unicode release. The one fold is ASCII's:
 * {@code A}-{@code Z} read as {@code a}-{@code z}, in an LDH label, an A-label or a U-label alike, which is how DNS
 * has always compared.
 *
 * <p><b>The boundaries an operator meets</b>, each refused with its fix: uppercase beyond ASCII ({@code BÜCHER} --
 * IDNA2008 has no uppercase U-label), a trailing dot ({@code example.com.}), percent-encoding (URI syntax, not a
 * name), and a last label beginning with a digit, which keeps a dotted-quad from ever being a host name. Labels
 * are at most 63 octets and the name at most 253, counted in the A-label form.
 */
public final class HostName implements Host {

    private final String unicode;
    private final String ascii;
    private final boolean idn;

    private HostName(String unicode, String ascii, boolean idn) {
        this.unicode = unicode;
        this.ascii = ascii;
        this.idn = idn;
    }

    /** The host name {@code text} spells, in any spelling of it. */
    public static HostName parse(String text) {
        if (text.isEmpty()) {
            throw new HostSyntaxException("is empty, and a host name has at least one label", text);
        }
        if (text.indexOf('%') >= 0) {
            throw new HostSyntaxException("is percent-encoded, which is URI syntax rather than a host name -- "
                    + "write the characters themselves", text);
        }
        if (text.endsWith(".")) {
            throw new HostSyntaxException("ends with a dot, and a host name carries no trailing dot -- write '"
                    + text.substring(0, text.length() - 1) + "'", text);
        }
        List<String> unicodeLabels = new ArrayList<>(4);
        List<String> asciiLabels = new ArrayList<>(4);
        boolean idn = false;
        int start = 0;
        while (true) {
            int dot = text.indexOf('.', start);
            String written = text.substring(start, dot < 0 ? text.length() : dot);
            if (written.isEmpty()) {
                throw new HostSyntaxException("has an empty label -- two dots together, or a leading dot", text);
            }
            String label = asciiLowercase(written);
            if (isAscii(label)) {
                if (label.startsWith("xn--")) {
                    String uLabel = Punycode.decode(label.substring(4));
                    if (uLabel == null || isAscii(uLabel)) {
                        throw new HostSyntaxException("has the label '" + written + "', which begins 'xn--' and "
                                + "is not an A-label: its rest does not decode to an internationalized label", text);
                    }
                    String problem = uLabelProblem(uLabel);
                    if (problem != null) {
                        throw new HostSyntaxException("has the A-label '" + written + "', whose U-label '" + uLabel
                                + "' " + problem, text);
                    }
                    String reencoded = Punycode.encode(uLabel);
                    if (reencoded == null || !reencoded.equals(label.substring(4))) {
                        throw new HostSyntaxException("has the label '" + written + "', which is not the A-label "
                                + "of its own U-label '" + uLabel + "'", text);
                    }
                    unicodeLabels.add(uLabel);
                    idn = true;
                } else {
                    String problem = ldhProblem(label);
                    if (problem != null) {
                        throw new HostSyntaxException("has the label '" + written + "', which " + problem, text);
                    }
                    unicodeLabels.add(label);
                }
                asciiLabels.add(label);
            } else {
                String problem = uLabelProblem(label);
                if (problem != null) {
                    throw new HostSyntaxException("has the label '" + written + "', which " + problem
                            + suggestion(text), text);
                }
                String encoded = Punycode.encode(label);
                if (encoded == null) {
                    throw new HostSyntaxException("has the label '" + written + "', too long to encode", text);
                }
                unicodeLabels.add(label);
                asciiLabels.add("xn--" + encoded);
                idn = true;
            }
            String asciiLabel = asciiLabels.getLast();
            if (asciiLabel.length() > 63) {
                throw new HostSyntaxException("has the label '" + written + "', which is " + asciiLabel.length()
                        + " octets as an A-label, and a label is at most 63", text);
            }
            if (dot < 0) {
                break;
            }
            start = dot + 1;
        }
        String last = unicodeLabels.getLast();
        if (Character.getType(last.codePointAt(0)) == Character.DECIMAL_DIGIT_NUMBER) {
            throw new HostSyntaxException("ends in a label beginning with a digit, and a host name's last label "
                    + "begins with a letter -- a dotted-quad is an IPv4 address, not a host name", text);
        }
        if (idn && unicodeLabels.stream().anyMatch(BidiRule::hasRightToLeft)) {
            for (String label : unicodeLabels) {
                var violation = BidiRule.violation(label);
                if (violation.isPresent()) {
                    throw new HostSyntaxException("holds right-to-left text, and its label '" + label + "' breaks "
                            + "the Bidi rule: " + violation.get(), text);
                }
            }
        }
        String ascii = String.join(".", asciiLabels);
        if (ascii.length() > 253) {
            throw new HostSyntaxException("is " + ascii.length() + " octets as A-labels, and a host name is at "
                    + "most 253", text);
        }
        return new HostName(idn ? String.join(".", unicodeLabels) : ascii, ascii, idn);
    }

    /** The name with each internationalized label as its U-label: the canonical text, for display and an IRI. */
    public String unicode() {
        return unicode;
    }

    /** The name with each internationalized label as its A-label, for DNS, a URI and HTTP. */
    public String ascii() {
        return ascii;
    }

    /** Whether any label is internationalized -- written as a U-label or an A-label alike. */
    public boolean isIdn() {
        return idn;
    }

    @Override
    public String text() {
        return unicode;
    }

    /** Two host names are equal when they name one domain, however each was spelled. */
    @Override
    public boolean equals(Object other) {
        return other instanceof HostName that && ascii.equals(that.ascii);
    }

    @Override
    public int hashCode() {
        return ascii.hashCode();
    }

    /** The canonical text: {@link #unicode()}. */
    @Override
    public String toString() {
        return unicode;
    }

    /** RFC 1123 §2.1 over a lowercase ASCII label that is not an A-label: why it is not one, or null. */
    private static String ldhProblem(String label) {
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-')) {
                return c == '_'
                        ? "holds '_', a DNS name character and not a host name one: a label is letters, digits and "
                                + "hyphens"
                        : "holds '" + c + "', and a label is letters, digits and hyphens";
            }
        }
        if (label.charAt(0) == '-' || label.charAt(label.length() - 1) == '-') {
            return "begins or ends with a hyphen";
        }
        if (label.length() >= 4 && label.charAt(2) == '-' && label.charAt(3) == '-') {
            return "has hyphens in its third and fourth places, which are reserved for A-labels (RFC 5890 §2.3.1)";
        }
        return null;
    }

    /** RFC 5891 §5.4's U-label checks over an ASCII-folded label: why it is not one, or null. */
    private static String uLabelProblem(String label) {
        if (!Nfc.of(label).equals(label)) {
            return "is not in Normalization Form C";
        }
        if (label.length() >= 4 && label.charAt(2) == '-' && label.charAt(3) == '-') {
            return "has hyphens in its third and fourth places (RFC 5891 §4.2.3.1)";
        }
        if (label.charAt(0) == '-' || label.charAt(label.length() - 1) == '-') {
            return "begins or ends with a hyphen";
        }
        int firstType = Character.getType(label.codePointAt(0));
        if (firstType == Character.NON_SPACING_MARK || firstType == Character.COMBINING_SPACING_MARK
                || firstType == Character.ENCLOSING_MARK) {
            return "begins with a combining mark (RFC 5891 §4.2.3.2)";
        }
        for (int i = 0; i < label.length(); ) {
            int cp = label.codePointAt(i);
            switch (IdnaProperty.of(cp)) {
                case PVALID -> { }
                case CONTEXTJ, CONTEXTO -> {
                    if (!IdnaProperty.contextual(label, i)) {
                        return "holds U+%04X where RFC 5892's contextual rule for it does not hold".formatted(cp);
                    }
                }
                case UNASSIGNED -> {
                    return "holds U+%04X, which is not assigned in Unicode %s, this processor's version"
                            .formatted(cp, Xid.UNICODE_VERSION);
                }
                case DISALLOWED -> {
                    String lower = Character.toString(cp).toLowerCase(Locale.ROOT);
                    return !lower.equals(Character.toString(cp))
                            ? "holds the uppercase U+%04X, and a host name has no uppercase beyond ASCII"
                                    .formatted(cp)
                            : "holds U+%04X, which IDNA2008 does not permit in a host name (RFC 5892)".formatted(cp);
                }
            }
            i += Character.charCount(cp);
        }
        return null;
    }

    /** The lowercase, NFC spelling of {@code text} as a fix to name, where that would be a host name. */
    private static String suggestion(String text) {
        String fixed = Nfc.of(text.toLowerCase(Locale.ROOT));
        if (fixed.equals(text)) {
            return "";
        }
        try {
            parse(fixed);
            return " -- write '" + fixed + "'";
        } catch (HostSyntaxException e) {
            return "";
        }
    }

    private static String asciiLowercase(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                char[] chars = text.toCharArray();
                for (int j = i; j < chars.length; j++) {
                    if (chars[j] >= 'A' && chars[j] <= 'Z') {
                        chars[j] += 'a' - 'A';
                    }
                }
                return new String(chars);
            }
        }
        return text;
    }

    private static boolean isAscii(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) >= 0x80) {
                return false;
            }
        }
        return true;
    }
}
