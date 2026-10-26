package io.ltr8.net;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A media type (RFC 6838) with its parameters (RFC 9110 §8.3.1): {@code text/html;charset=utf-8}. The value is the
 * type, the subtype and a map of parameters, so every spelling of one is one value.
 *
 * <p><b>Equality is the RFCs'.</b> The type, subtype and parameter names compare without ASCII case; parameters are a
 * map, so their order carries no meaning and a repeated name is refused; a quoted value equals its token form. A
 * parameter value keeps its case, except {@code charset}'s, which folds (RFC 9110 §8.3.2) -- and only
 * {@code charset}'s, a closed list, since which other registrations fold their values is the registry's to say and
 * not a rule a processor can hold.
 *
 * <p><b>The grammar.</b> The type and subtype are RFC 6838 §4.2's {@code restricted-name}: at most 127 characters,
 * the first a letter or digit. Parameters are RFC 9110's: {@code ;} with optional whitespace around it, a token name,
 * {@code =}, and a token or quoted-string value. Nothing beyond US-ASCII. A media range ({@code text/*}) is
 * {@code Accept}'s syntax for a pattern over media types, and not one.
 *
 * <p>{@link #toString()} is the canonical text: lowercase names, the {@code charset} value folded, parameters sorted
 * by name with each value a token where it is one and a quoted-string otherwise, joined by {@code ;} with no
 * whitespace.
 */
public final class MediaType {

    private final String type;
    private final String subtype;
    private final Map<String, String> parameters;

    private MediaType(String type, String subtype, Map<String, String> parameters) {
        this.type = type;
        this.subtype = subtype;
        this.parameters = Collections.unmodifiableMap(parameters);
    }

    /** The media type {@code text} spells. */
    public static MediaType parse(String text) {
        int slash = text.indexOf('/');
        if (slash < 0) {
            throw new MediaTypeSyntaxException("has no '/', and a media type is a type and a subtype", text);
        }
        int end = slash + 1;
        while (end < text.length() && text.charAt(end) != ';' && text.charAt(end) != ' '
                && text.charAt(end) != '\t') {
            end++;
        }
        String type = text.substring(0, slash);
        String subtype = text.substring(slash + 1, end);
        if (type.equals("*") || subtype.equals("*")) {
            throw new MediaTypeSyntaxException("is a media range, Accept's pattern over media types, and not a "
                    + "media type", text);
        }
        requireRestrictedName(text, "type", type);
        requireRestrictedName(text, "subtype", subtype);
        Map<String, String> parameters = new TreeMap<>();
        int i = end;
        while (i < text.length()) {
            i = skipWhitespace(text, i);
            if (i == text.length()) {
                throw new MediaTypeSyntaxException("ends in whitespace", text);
            }
            if (text.charAt(i) != ';') {
                throw new MediaTypeSyntaxException("has '" + text.charAt(i) + "' where ';' or the end belongs", text);
            }
            i = skipWhitespace(text, i + 1);
            if (i == text.length() || text.charAt(i) == ';') {
                continue;
            }
            int nameEnd = i;
            while (nameEnd < text.length() && isTchar(text.charAt(nameEnd))) {
                nameEnd++;
            }
            String name = text.substring(i, nameEnd).toLowerCase(Locale.ROOT);
            if (name.isEmpty() || nameEnd == text.length() || text.charAt(nameEnd) != '=') {
                throw new MediaTypeSyntaxException("has a parameter that is not a token name, '=', and a value", text);
            }
            int valueStart = nameEnd + 1;
            String value;
            if (valueStart < text.length() && text.charAt(valueStart) == '"') {
                StringBuilder quoted = new StringBuilder();
                int j = valueStart + 1;
                while (true) {
                    if (j >= text.length()) {
                        throw new MediaTypeSyntaxException("has a quoted parameter value with no closing '\"'", text);
                    }
                    char c = text.charAt(j);
                    if (c == '"') {
                        break;
                    }
                    if (c == '\\') {
                        if (j + 1 >= text.length() || !isQuotedPairChar(text.charAt(j + 1))) {
                            throw new MediaTypeSyntaxException("has a '\\' escaping nothing it may escape", text);
                        }
                        quoted.append(text.charAt(j + 1));
                        j += 2;
                        continue;
                    }
                    if (!isQdtext(c)) {
                        throw new MediaTypeSyntaxException(("holds U+%04X in a quoted parameter value, where it "
                                + "may not stand").formatted((int) c), text);
                    }
                    quoted.append(c);
                    j++;
                }
                value = quoted.toString();
                i = j + 1;
            } else {
                int valueEnd = valueStart;
                while (valueEnd < text.length() && isTchar(text.charAt(valueEnd))) {
                    valueEnd++;
                }
                if (valueEnd == valueStart) {
                    throw new MediaTypeSyntaxException("has the parameter '" + name + "' with no value", text);
                }
                value = text.substring(valueStart, valueEnd);
                i = valueEnd;
            }
            if (name.equals("charset")) {
                value = value.toLowerCase(Locale.ROOT);
            }
            if (parameters.putIfAbsent(name, value) != null) {
                throw new MediaTypeSyntaxException("has the parameter '" + name + "' twice, and parameters are a "
                        + "map", text);
            }
        }
        return new MediaType(type.toLowerCase(Locale.ROOT), subtype.toLowerCase(Locale.ROOT), parameters);
    }

    /** The top-level type, lowercase: {@code text} in {@code text/html}. */
    public String type() {
        return type;
    }

    /** The subtype, lowercase: {@code ld+json} in {@code application/ld+json}. */
    public String subtype() {
        return subtype;
    }

    /** RFC 6839's structured suffix, the subtype's text after its last {@code +}: {@code json} in {@code ld+json}. */
    public Optional<String> suffix() {
        int plus = subtype.lastIndexOf('+');
        return plus < 0 ? Optional.empty() : Optional.of(subtype.substring(plus + 1));
    }

    /** The parameters, names lowercase and sorted, values as read, {@code charset}'s folded. */
    public Map<String, String> parameters() {
        return parameters;
    }

    /** Two media types are equal when type, subtype and parameter map are, however each was spelled. */
    @Override
    public boolean equals(Object other) {
        return other instanceof MediaType that && type.equals(that.type) && subtype.equals(that.subtype)
                && parameters.equals(that.parameters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, subtype, parameters);
    }

    /** The canonical text. */
    @Override
    public String toString() {
        StringBuilder text = new StringBuilder(type).append('/').append(subtype);
        parameters.forEach((name, value) -> {
            text.append(';').append(name).append('=');
            if (!value.isEmpty() && value.chars().allMatch(c -> isTchar((char) c))) {
                text.append(value);
            } else {
                text.append('"');
                for (int i = 0; i < value.length(); i++) {
                    char c = value.charAt(i);
                    if (c == '"' || c == '\\') {
                        text.append('\\');
                    }
                    text.append(c);
                }
                text.append('"');
            }
        });
        return text.toString();
    }

    private static void requireRestrictedName(String text, String part, String name) {
        if (name.isEmpty() || name.length() > 127) {
            throw new MediaTypeSyntaxException("has a " + part + " of " + name.length() + " characters, and a "
                    + part + " is 1 to 127 (RFC 6838 §4.2)", text);
        }
        if (!isAlphaNum(name.charAt(0))) {
            throw new MediaTypeSyntaxException("has a " + part + " beginning with '" + name.charAt(0) + "', and a "
                    + part + " begins with a letter or digit (RFC 6838 §4.2)", text);
        }
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(isAlphaNum(c) || "!#$&-^_.+".indexOf(c) >= 0)) {
                throw new MediaTypeSyntaxException(("has U+%04X in its " + part + ", and a " + part + " is letters, "
                        + "digits and ! # $ & - ^ _ . + (RFC 6838 §4.2)").formatted((int) c), text);
            }
        }
    }

    private static int skipWhitespace(String text, int i) {
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
            i++;
        }
        return i;
    }

    private static boolean isAlphaNum(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    /** RFC 9110 §5.6.2's {@code tchar}. */
    private static boolean isTchar(char c) {
        return isAlphaNum(c) || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
    }

    /** RFC 9110 §5.6.4's {@code qdtext}, without {@code obs-text}: nothing beyond US-ASCII. */
    private static boolean isQdtext(char c) {
        return c == '\t' || c == ' ' || c == 0x21 || (c >= 0x23 && c <= 0x5B) || (c >= 0x5D && c <= 0x7E);
    }

    /** What a {@code quoted-pair} may escape: HTAB, SP and VCHAR. */
    private static boolean isQuotedPairChar(char c) {
        return c == '\t' || (c >= 0x20 && c <= 0x7E);
    }
}
