package io.ltr8.tson.schema.meta;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The meta-kernel's {@code text_type} constructor -- the Unicode code point sequence type every
 * other text-shaped atom composes with. Pure constraint values, no parsing/validation behavior --
 * {@code tson-parser}'s {@code TextParser} holds one of these and does the actual reading/writing.
 */
public record TextType(
        Optional<Integer> minLength,
        Optional<Integer> maxLength,
        Optional<Integer> length,
        Optional<Pattern> pattern) {

    /** {@code text => !text_type {}} -- the unconstrained text type. */
    public static final TextType UNCONSTRAINED =
            new TextType(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
}
