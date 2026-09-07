package io.ltr8.tson.json;

/**
 * RFC 8259 string quoting -- the write-side inverse of {@code JsonLexer}'s escape decoding, and the
 * only place this module writes a string.
 *
 * <p>Escapes what RFC 8259 requires and nothing more: the quotation mark, the reverse solidus, and the
 * control characters U+0000 through U+001F, the five of which have short forms taking them. The
 * solidus is left alone -- {@code \/} is legal and pointless to emit -- and every other character is
 * written as itself, since [TSON-JSON] §3.1 makes the document UTF-8 and a UTF-8 document has no
 * reason to spell an ordinary character as an escape.
 *
 * <p>An unpaired surrogate cannot reach here: a parsed string was refused one by the lexer (§3.1), and
 * a constructed one carries whatever the caller built. A lone surrogate in a hand-built value is
 * written as its own {@code \\u} escape rather than emitted raw, which keeps the output well-formed
 * UTF-8 -- the value is still one this profile would refuse on the way back in, and refusing it there
 * is where §3.1 puts the rule.
 */
final class JsonText {

    private JsonText() {
    }

    static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2);
        quote(value, out);
        return out.toString();
    }

    static void quote(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || isUnpairedSurrogate(value, i, c)) {
                        out.append("\\u%04x".formatted((int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /** Whether the surrogate at {@code i} stands alone -- a high one with no low after it, or a low one with no high before. */
    private static boolean isUnpairedSurrogate(String value, int i, char c) {
        if (Character.isHighSurrogate(c)) {
            return i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1));
        }
        if (Character.isLowSurrogate(c)) {
            return i == 0 || !Character.isHighSurrogate(value.charAt(i - 1));
        }
        return false;
    }
}
