package io.ltr8.tson.compiler.atom;

import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.regex.TsonRegex;
import io.ltr8.tson.schema.meta.TextType;

import java.util.Optional;

/**
 * Parses and validates against meta-kernel's {@code text_type} constructor -- the Unicode code
 * point sequence type every other text-shaped atom in this package composes with ({@code
 * UriParser} already does; {@code RegexParser} does explicitly, via a {@code TextType} field,
 * rather than duplicating its constraint checks). Holds a {@link TextType} -- the pure constraint
 * values, unchanged by this split -- rather than declaring those fields itself.
 *
 * <p><b>Not registered in {@link BuiltinTypeVocabulary} yet, though §5 now requires it.</b> {@code !text} is
 * Part 1's unconstrained text atom -- every token accepted, the host value the token's text -- so the
 * schemaless path should resolve it here; it does not, and this class has no {@code TYPENAME} constant to
 * register with ({@code BACKLOG.md}). It serves meanwhile as groundwork
 * for Part 2's schema layer, which will resolve {@code text_type}/{@code text} through actual schema
 * machinery rather than a fixed §5 name table.
 */
public record TextParser(TextType constraints) implements AtomType<String> {

    /** {@code text => !text_type {}} -- the unconstrained text type. */
    public static final TextParser UNCONSTRAINED = new TextParser(TextType.UNCONSTRAINED);

    public TextParser(Optional<Integer> minLength, Optional<Integer> maxLength, Optional<Integer> length,
                       Optional<String> pattern) {
        this(new TextType(minLength, maxLength, length, pattern));
    }

    @Override
    public String read(TokenValue token) {
        String text = token.text();
        validate(text);
        return text;
    }

    @Override
    public String write(String value) {
        return value;
    }

    private void validate(String text) {
        constraints.length().ifPresent(len -> {
            if (text.length() != len) {
                throw new AtomValidationException(
                        "'" + text + "' is " + text.length() + " characters, expected exactly " + len,
                        "exactly " + len + " characters");
            }
        });
        constraints.minLength().ifPresent(min -> {
            if (text.length() < min) {
                throw new AtomValidationException(
                        "'" + text + "' is " + text.length() + " characters, less than the minimum " + min,
                        "at least " + min + " characters");
            }
        });
        constraints.maxLength().ifPresent(max -> {
            if (text.length() > max) {
                throw new AtomValidationException(
                        "'" + text + "' is " + text.length() + " characters, more than the maximum " + max,
                        "at most " + max + " characters");
            }
        });
        // The pattern is I-Regexp (RFC 9485), matched via tson-regex (linear-time, ReDoS-safe), not
        // java.util.regex; it was already validated well-formed when the schema resolved (see RegexParser).
        constraints.pattern().ifPresent(p -> {
            if (!TsonRegex.parse(p).matches(text)) {
                throw new AtomValidationException("'" + text + "' does not match the required pattern " + p,
                        "matching " + p);
            }
        });
    }
}
