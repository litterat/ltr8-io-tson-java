package io.ltr8.tson.parser.resolver.vocab;

import io.ltr8.tson.parser.ast.TokenValue;
import io.ltr8.tson.schema.meta.TextType;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Parses and validates against meta-kernel's {@code text_type} constructor -- the Unicode code
 * point sequence type every other text-shaped atom in this package composes with ({@code
 * UriParser} already does; {@code RegexParser} does explicitly, via a {@code TextType} field,
 * rather than duplicating its constraint checks). Holds a {@link TextType} -- the pure constraint
 * values, unchanged by this split -- rather than declaring those fields itself.
 *
 * <p>Not part of Part 1's published built-in vocabulary (§5) -- see {@code SPEC-FEEDBACK.md} #9 --
 * so unlike every other {@code AtomType} in this package, {@code TextParser} is never registered in
 * {@link BuiltinTypeVocabulary} and has no {@code TYPENAME} constant. It exists purely as groundwork
 * for Part 2's schema layer, which will resolve {@code text_type}/{@code text} through actual schema
 * machinery rather than a fixed §5 name table.
 */
public record TextParser(TextType constraints) implements AtomType<String> {

    /** {@code text => !text_type {}} -- the unconstrained text type. */
    public static final TextParser UNCONSTRAINED = new TextParser(TextType.UNCONSTRAINED);

    public TextParser(Optional<Integer> minLength, Optional<Integer> maxLength, Optional<Integer> length,
                       Optional<Pattern> pattern) {
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
                        "'" + text + "' is " + text.length() + " characters, expected exactly " + len);
            }
        });
        constraints.minLength().ifPresent(min -> {
            if (text.length() < min) {
                throw new AtomValidationException(
                        "'" + text + "' is " + text.length() + " characters, less than the minimum " + min);
            }
        });
        constraints.maxLength().ifPresent(max -> {
            if (text.length() > max) {
                throw new AtomValidationException(
                        "'" + text + "' is " + text.length() + " characters, more than the maximum " + max);
            }
        });
        constraints.pattern().ifPresent(p -> {
            if (!p.matcher(text).matches()) {
                throw new AtomValidationException("'" + text + "' does not match the required pattern " + p);
            }
        });
    }
}
