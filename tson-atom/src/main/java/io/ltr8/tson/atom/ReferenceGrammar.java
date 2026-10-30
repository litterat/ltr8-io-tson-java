package io.ltr8.tson.atom;

import io.ltr8.tson.atom.number.NumberGrammar;
import io.ltr8.tson.schema.meta.Token;
import io.ltr8.tson.schema.meta.TypeArgument;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.ArrayList;
import java.util.List;

/**
 * The reference grammar, read from a token's text into the {@link TypeRef} it spells (SPEC-FEEDBACK.md #12): the
 * value of the kernel's {@code reference} atom, the one parser both encodings read a reference-typed slot with.
 *
 * <pre>
 *   reference = segment *("." segment) [ws "&lt;" ws arguments ws "&gt;"]
 *   segment   = identifier                       ; [TSON-DATA] §7.7
 *   arguments = argument *(separator argument)   ; separator = ws "," ws / ws1, as [TSON-SCHEMA] §12.1's type-args
 *   argument  = number / quoted / reference      ; a number or a quoted token is a value, anything else a reference
 * </pre>
 *
 * <p><b>The same parsed form as the schema grammar's type positions</b>, which keep their own parser
 * ({@code TsonSchemaParser.parseTypeArg}) because a type position also admits the sugar forms a reference never
 * holds: both classify an argument by its token alone, a number or a quoted token as a value and every other token
 * as a reference, and leave the parameter to judge which it binds (§5.10). Whitespace inside an argument list is
 * free, and {@link TypeRef#text} is the one canonical spelling, so {@code result< T >} and {@code result<T>} parse
 * to one reference. A quoted argument is {@code "} delimited, with {@code \"}, {@code \\}, {@code \n}, {@code \r},
 * {@code \t} and {@code \}{@code uXXXX} escapes.
 *
 * <p>The grammar alone: what the head and each argument resolve to is the position's to judge.
 */
public final class ReferenceGrammar {

    private ReferenceGrammar() {
    }

    /**
     * {@code text} as the reference it spells.
     *
     * @throws AtomParseException where it spells none, naming what failed
     */
    public static TypeRef parse(String text) {
        Cursor cursor = new Cursor(text);
        TypeRef reference = cursor.reference();
        if (!cursor.atEnd()) {
            throw cursor.error("'" + cursor.rest() + "' follows the reference");
        }
        return reference;
    }

    /** Whether {@code text} spells a reference carrying an argument list -- an application, written as one token. */
    public static boolean isApplication(String text) {
        if (text.indexOf('<') < 0) {
            return false;
        }
        try {
            return !parse(text).arguments().isEmpty();
        } catch (AtomParseException e) {
            return false;
        }
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

        TypeRef reference() {
            int start = at;
            String head = word();
            if (head.isEmpty()) {
                throw error(atEnd() ? "a reference names something, and this is empty"
                        : "expected a name, found '" + text.charAt(at) + "'");
            }
            segments(head);
            int afterHead = at;
            skipWhitespace();
            if (atEnd() || text.charAt(at) != '<') {
                at = afterHead;
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
                    return new TypeRef(head, arguments);
                }
                arguments.add(argument());
                int beforeSeparator = at;
                skipSeparators();
                if (at == beforeSeparator && !atEnd() && text.charAt(at) != '>') {
                    throw error("expected a separator or '>', found '" + text.charAt(at) + "'");
                }
            }
        }

        private TypeArgument argument() {
            if (text.charAt(at) == '"') {
                return new TypeArgument.Value(new Token(quoted(), Token.Form.SINGLE_LINE_QUOTED));
            }
            int start = at;
            String word = word();
            if (!word.isEmpty() && NumberGrammar.tryParse(word).isPresent()) {
                return new TypeArgument.Value(new Token(word, Token.Form.UNQUOTED));
            }
            at = start;
            return new TypeArgument.Ref(reference());
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
