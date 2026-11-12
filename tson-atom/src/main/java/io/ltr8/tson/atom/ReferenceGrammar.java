package io.ltr8.tson.atom;

import io.ltr8.tson.schema.meta.TypeRef;

import java.util.ArrayList;
import java.util.List;

/**
 * [TSON-DATA] §7.4's reference production, read from text into the {@link TypeRef} it spells: what
 * a JSON string at a reference-typed position holds, JSON having no unquoted form, and the text form
 * {@link TypeRef#text} writes. The TSON text reader builds the same tree from tokens ({@code TsonDataStream}).
 *
 * <p><b>A reference is a name</b> ([TSON-SCHEMA] §4.2). The grammar still parses an argument list, so a value
 * carrying one is read whole and then refused, as a validation error naming how an application is written: in data
 * by the entry a declaration gives it, in a schema as a type written in place.
 *
 * <pre>
 *   reference   = qualified-name ["&lt;" ws type-args ws "&gt;"]   ; "&lt;" adjacent to the name
 *   type-args   = type-arg *(separator type-arg) [ws ","]         ; separator = ws "," ws / ws1
 *   type-arg    = reference                                       ; a name or an application, never a literal
 * </pre>
 *
 * <p>An argument is a type ([TSON-SCHEMA] §5.10), so a token that is no reference -- a number, a quoted token -- is
 * no argument, and {@code maxint<10>} spells no reference. Whitespace inside an argument list is free, so {@code
 * result< T >} and {@code result<T>} parse to one reference.
 *
 * <p>The grammar alone: what the head and each argument resolve to is the position's to judge.
 */
public final class ReferenceGrammar {

    private ReferenceGrammar() {
    }

    /**
     * {@code text} as the name it spells.
     *
     * @throws AtomParseException where it spells no reference, naming what failed
     * @throws AtomValidationException where it spells an application, which no reference is
     */
    public static TypeRef parse(String text) {
        Spelled spelled = spelled(text);
        if (!spelled.arguments().isEmpty()) {
            throw new AtomValidationException("'" + spelled.text() + "' is an application, and a reference is a name "
                    + "([TSON-SCHEMA] §4.2): in a data document, write the name a declaration gives it ('my_"
                    + spelled.head() + " => " + spelled.text() + "', then 'my_" + spelled.head() + "'); in a schema, "
                    + "write it at a type slot, where it is the type written in place '{ of: !meta.apply { head: "
                    + spelled.head() + "  arguments: [...] } }'", "a name");
        }
        return TypeRef.of(spelled.head());
    }

    /**
     * {@code text} as the grammar reads it, an argument list included: what a reference spells before a position
     * judges it.
     *
     * @throws AtomParseException where it spells no reference, naming what failed
     */
    public static Spelled spelled(String text) {
        Cursor cursor = new Cursor(text);
        Spelled reference = cursor.reference();
        if (!cursor.atEnd()) {
            throw cursor.error("'" + cursor.rest() + "' follows the reference");
        }
        return reference;
    }

    /** A reference as the grammar spells it: a qualified name, and the argument list written after it, if any. */
    public record Spelled(String head, List<Spelled> arguments) {

        public Spelled {
            arguments = List.copyOf(arguments);
        }

        /** The canonical text: no whitespace but one space between arguments. */
        public String text() {
            if (arguments.isEmpty()) {
                return head;
            }
            StringBuilder out = new StringBuilder(head).append('<');
            for (int i = 0; i < arguments.size(); i++) {
                out.append(i == 0 ? "" : " ").append(arguments.get(i).text());
            }
            return out.append('>').toString();
        }
    }

    /** Whether {@code text} is a qualified name: segments joined by {@code .}, each an identifier (§7.7). */
    public static boolean isQualifiedName(String text) {
        for (String segment : text.split("\\.", -1)) {
            if (segment.isEmpty() || IdentifierGrammar.validate(segment).isPresent()) {
                return false;
            }
        }
        return true;
    }

    private static final class Cursor {

        private final String text;
        private int at;

        Cursor(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return at == text.length();
        }

        String rest() {
            return text.substring(at);
        }

        Spelled reference() {
            int start = at;
            String head = word();
            if (head.isEmpty()) {
                throw error(atEnd() ? "a reference names something, and this is empty"
                        : "expected a name, found '" + text.charAt(at) + "'");
            }
            segments(head);
            int afterHead = at;
            if (atEnd() || text.charAt(at) != '<') {
                return new Spelled(head, List.of());
            }
            at++;
            List<Spelled> arguments = new ArrayList<>();
            skipSeparators();
            while (true) {
                if (atEnd()) {
                    throw error("'<' after '" + text.substring(start, afterHead) + "' has no closing '>'");
                }
                if (text.charAt(at) == '>') {
                    if (arguments.isEmpty()) {
                        throw error("an argument list holds at least one argument, and '" + head + "<>' holds none");
                    }
                    at++;
                    return new Spelled(head, arguments);
                }
                arguments.add(argument());
                int beforeSeparator = at;
                skipSeparators();
                if (at == beforeSeparator && !atEnd() && text.charAt(at) != '>') {
                    throw error("expected a separator or '>', found '" + text.charAt(at) + "'");
                }
            }
        }

        private Spelled argument() {
            if (text.charAt(at) == '"') {
                throw error("an argument is a reference, a name or an application, and a quoted token is neither");
            }
            int start = at;
            String word = word();
            if (word.isEmpty()) {
                throw error("expected an argument, found '" + text.charAt(at) + "'");
            }
            if (!isQualifiedName(word)) {
                throw error("'" + word + "' is no argument: an argument is a reference, a name or an application, "
                        + "and never a literal token");
            }
            at = start;
            return reference();
        }

        /** Each {@code .}-separated segment of {@code head} an identifier, none empty. */
        private void segments(String head) {
            for (String segment : head.split("\\.", -1)) {
                if (segment.isEmpty()) {
                    throw error("'" + head + "' has an empty segment -- each segment between the dots is a name");
                }
                IdentifierGrammar.validate(segment).ifPresent(violation -> {
                    throw error("'" + segment + "' is no name: " + violation);
                });
            }
        }

        /** The run of characters up to whitespace, a separator, an angle bracket or a quote. */
        private String word() {
            int start = at;
            while (!atEnd() && !isWhitespace(text.charAt(at)) && "<>,\"".indexOf(text.charAt(at)) < 0) {
                at++;
            }
            return text.substring(start, at);
        }

        private void skipWhitespace() {
            while (!atEnd() && isWhitespace(text.charAt(at))) {
                at++;
            }
        }

        private void skipSeparators() {
            skipWhitespace();
            if (!atEnd() && text.charAt(at) == ',') {
                at++;
                skipWhitespace();
            }
        }

        private static boolean isWhitespace(char c) {
            return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000B' || c == '\f';
        }

        AtomParseException error(String detail) {
            return new AtomParseException("'" + text + "' is no reference -- " + detail, "a reference");
        }
    }
}
