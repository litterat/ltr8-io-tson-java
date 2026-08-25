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
 * <p><b>{@code !text} is §5.5's unconstrained text atom</b>: every token accepted, the host value the
 * token's text. It adds nothing an unannotated token's base resolution (§4.4) does not already give, and
 * that is the point -- it lets the string case be asserted, so a quoted numeric under {@code !text} is
 * unambiguously the string rather than a number that happened to be quoted.
 *
 * <p><b>No reverse mapping.</b> {@code VocabularyAtoms} maps a host class to the name a writer annotates it
 * with, and this one's host class is {@code String} -- what both writers emit bare. An entry there would put
 * {@code !text} on every string in every document.
 */
public record TextParser(TextType constraints) implements AtomType<String> {

    /** The §5.5 annotation name this atom is reached by. */
    public static final String TYPENAME = "text";

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
