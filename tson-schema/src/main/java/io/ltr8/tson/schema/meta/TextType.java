package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Typename;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The meta-kernel's {@code text_type} constructor -- the Unicode code point sequence type every
 * other text-shaped atom composes with. Pure constraint values, no parsing/validation behavior --
 * {@code tson-parser}'s {@code TextParser} holds one of these and does the actual reading/writing.
 *
 * <p>Also a {@link TypeBody}/{@link Atom} variant (added 2026-07-23, alongside {@code uri_type}/
 * {@code regex_type} below): {@code text => !text_type {}} is a constructor-application instance
 * (§5.5) whose resolved body is exactly {@link #UNCONSTRAINED} -- every field here is {@code
 * Optional}, so an empty {@code {}} body needs no design work beyond what {@link IntegerType}
 * already established for the same shape.
 */
@Typename(name = "text_type")
public record TextType(
        @Field("min_length") Optional<Integer> minLength,
        @Field("max_length") Optional<Integer> maxLength,
        Optional<Integer> length,
        Optional<Pattern> pattern) implements TypeBody, Atom {

    /** {@code text => !text_type {}} -- the unconstrained text type. */
    public static final TextType UNCONSTRAINED =
            new TextType(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
}
