package io.ltr8.tson.atom;

import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.SiblingRef;
import io.ltr8.tson.schema.meta.Token;
import io.ltr8.tson.schema.meta.TypeArgument;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.ArrayList;
import java.util.List;

/**
 * [TSON-DATA] §7.4's reference production, read from text into the {@link Reference} it spells: what
 * a JSON string at a reference-typed position holds, JSON having no unquoted form, and the text form
 * {@link Reference#text} writes. The TSON text reader builds the same tree from tokens ({@code TsonDataStream}).
 *
 * <pre>
 *   reference   = sibling-ref / qualified-name ["&lt;" ws type-args ws "&gt;"]   ; "&lt;" adjacent to the name
 *   sibling-ref = identifier "!"                                  ; one segment; the text ends at the "!"
 *   type-args   = type-arg *(separator type-arg) [ws ","]         ; separator = ws "," ws / ws1
 *   type-arg    = reference / token                               ; a qualified name is a reference, any other
 *                                                                 ; token (a number, a quoted token) a value
 * </pre>
 *
 * <p>A quoted argument is {@code "} delimited, with {@code \"}, {@code \\}, {@code \n}, {@code \r}, {@code \t}
 * and {@code \}{@code uXXXX} escapes, and is never a reference. Whitespace inside an argument list is free, so
 * {@code result< T >} and {@code result<T>} parse to one reference.
 *
 * <p><b>Two entry points, one production.</b> {@link #parseReference} reads every variant, a sibling reference
 * ({@code kind!}) among them, for the one slot that holds one, a record field's type. {@link #parse} reads a {@link
 * TypeRef} and refuses a sibling reference by the position rule ([TSON-SCHEMA] §5.2): every other reference-typed
 * position names a type. A sibling reference is never an argument, so one inside an argument list is refused by the
 * same rule wherever the reference stands.
 *
 * <p>The grammar alone: what the head and each argument resolve to is the position's to judge.
 */
public final class ReferenceGrammar {

    private ReferenceGrammar() {
    }

    /**
     * {@code text} as the type reference it spells: a name or an application.
     *
     * @throws AtomParseException where it spells none, naming what failed, and where it spells a sibling reference,
     *                            which stands only at a record field's type
     */
    public static TypeRef parse(String text) {
        return switch (parseReference(text)) {
            case TypeRef reference -> reference;
            case SiblingRef sibling -> throw new AtomParseException(sibling.refusedAt("a position typed by a type "
                    + "reference"), "a type's name");
        };
    }

    /**
     * {@code text} as the reference it spells: a name, an application or a sibling reference.
     *
     * @throws AtomParseException where it spells none, naming what failed
     */
    public static Reference parseReference(String text) {
        Cursor cursor = new Cursor(text);
        Reference reference = cursor.reference();
        if (!cursor.atEnd()) {
            throw cursor.error("'" + cursor.rest() + "' follows the reference");
        }
        return reference;
    }

    /**
     * Whether {@code field} may stand before a sibling reference's {@code !}: one identifier, a single segment
     * ([TSON-DATA] §7.4). A dotted name would be a chained projection, which the grammar does not have.
     */
    public static boolean isSiblingField(String field) {
        return !field.isEmpty() && field.indexOf('.') < 0 && IdentifierGrammar.validate(field).isEmpty();
    }

    /**
     * The names {@code reference} writes -- its head and every reference among its arguments, at any depth -- which a
     * position's name hygiene judges one by one, as it judges a bare name ([TSON-DATA] §8.2).
     */
    public static List<String> names(TypeRef reference) {
        List<String> names = new ArrayList<>();
        names.add(reference.name());
        for (TypeArgument argument : reference.arguments()) {
            if (argument instanceof TypeArgument.Ref ref) {
                names.addAll(names(ref.ref()));
            }
        }
        return names;
    }

    /**
     * Whether a position's name hygiene refuses the value it read: each name an application writes where the value
     * is one, the value itself otherwise.
     */
    public static boolean anyRefused(String value, java.util.function.Predicate<String> refuses) {
        if (value.indexOf('<') < 0) {
            return refuses.test(value);
        }
        for (String name : names(parse(value))) {
            if (refuses.test(name)) {
                return true;
            }
        }
        return false;
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

        Reference reference() {
            int start = at;
            String head = word();
            if (head.isEmpty()) {
                throw error(atEnd() ? "a reference names something, and this is empty"
                        : "expected a name, found '" + text.charAt(at) + "'");
            }
            if (head.endsWith("!")) {
                return sibling(head);
            }
            segments(head);
            int afterHead = at;
            if (atEnd() || text.charAt(at) != '<') {
                return TypeRef.of(head);
            }
            at++;
            List<TypeArgument> arguments = new ArrayList<>();
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
                    return TypeRef.of(head, arguments);
                }
                arguments.add(argument());
                int beforeSeparator = at;
                skipSeparators();
                if (at == beforeSeparator && !atEnd() && text.charAt(at) != '>') {
                    throw error("expected a separator or '>', found '" + text.charAt(at) + "'");
                }
            }
        }

        /**
         * {@code head}, a word ending in {@code !}, as the sibling reference it spells: the text ends at the mark,
         * and the field before it is one identifier.
         */
        private SiblingRef sibling(String head) {
            String field = head.substring(0, head.length() - 1);
            if (!isSiblingField(field)) {
                throw error("'" + field + "' is no field name a sibling reference can mark -- the '!' follows one "
                        + "identifier, a single segment");
            }
            if (!atEnd()) {
                throw error("the '!' of a sibling reference ends it, and '" + rest() + "' follows");
            }
            return new SiblingRef(field, null);
        }

        private TypeArgument argument() {
            if (text.charAt(at) == '"') {
                return new TypeArgument.Value(new Token(quoted(), Token.Form.SINGLE_LINE_QUOTED));
            }
            int start = at;
            String word = word();
            if (word.isEmpty()) {
                throw error("expected an argument, found '" + text.charAt(at) + "'");
            }
            if (word.endsWith("!") && isSiblingField(word.substring(0, word.length() - 1))) {
                throw new AtomParseException(new SiblingRef(word.substring(0, word.length() - 1), null)
                        .refusedAt("a template argument"), "a template argument");
            }
            if (!isQualifiedName(word)) {
                if (!atEnd() && text.charAt(at) == '<') {
                    throw error("'" + word + "' is no qualified name, so it takes no argument list");
                }
                return new TypeArgument.Value(new Token(word, Token.Form.UNQUOTED));
            }
            at = start;
            return new TypeArgument.Ref((TypeRef) reference());
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

        private String quoted() {
            StringBuilder out = new StringBuilder();
            at++;
            while (true) {
                if (atEnd()) {
                    throw error("a quoted argument has no closing '\"'");
                }
                char c = text.charAt(at++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw error("a quoted argument ends inside an escape");
                }
                char escaped = text.charAt(at++);
                switch (escaped) {
                    case '"', '\\', '/' -> out.append(escaped);
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (at + 4 > text.length()) {
                            throw error("a '\\u' escape needs four hex digits");
                        }
                        try {
                            out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                        } catch (NumberFormatException e) {
                            throw error("a '\\u' escape needs four hex digits");
                        }
                        at += 4;
                    }
                    default -> throw error("'\\" + escaped + "' is no escape");
                }
            }
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
