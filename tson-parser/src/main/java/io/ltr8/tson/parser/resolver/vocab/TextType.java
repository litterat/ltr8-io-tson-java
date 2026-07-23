package io.ltr8.tson.parser.resolver.vocab;

import io.ltr8.tson.parser.ast.TokenValue;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * meta-kernel's {@code text_type} constructor -- the Unicode code point sequence type every other
 * text-shaped atom in this package composes with ({@link UriType} already does; {@link RegexType}
 * does explicitly, via a {@code TextType} field, rather than duplicating its constraint checks).
 *
 * <p>Not part of Part 1's published built-in vocabulary (§5) -- see {@code SPEC-FEEDBACK.md} #9 --
 * so unlike every other {@code AtomType} in this package, {@code TextType} is never registered in
 * {@link BuiltinTypeVocabulary} and has no {@code TYPENAME} constant. It exists purely as groundwork
 * for Part 2's schema layer, which will resolve {@code text_type}/{@code text} through actual schema
 * machinery rather than a fixed §5 name table.
 */
public record TextType(
        Optional<Integer> minLength,
        Optional<Integer> maxLength,
        Optional<Integer> length,
        Optional<Pattern> pattern) implements AtomType<String> {

    /** {@code text => !text_type {}} -- the unconstrained text type. */
    public static final TextType UNCONSTRAINED =
            new TextType(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

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
        length.ifPresent(len -> {
            if (text.length() != len) {
                throw new AtomValidationException(
                        "'" + text + "' is " + text.length() + " characters, expected exactly " + len);
            }
        });
        minLength.ifPresent(min -> {
            if (text.length() < min) {
                throw new AtomValidationException(
                        "'" + text + "' is " + text.length() + " characters, less than the minimum " + min);
            }
        });
        maxLength.ifPresent(max -> {
            if (text.length() > max) {
                throw new AtomValidationException(
                        "'" + text + "' is " + text.length() + " characters, more than the maximum " + max);
            }
        });
        pattern.ifPresent(p -> {
            if (!p.matcher(text).matches()) {
                throw new AtomValidationException("'" + text + "' does not match the required pattern " + p);
            }
        });
    }
}
