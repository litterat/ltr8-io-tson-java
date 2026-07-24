package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Typename;

import java.util.Optional;

/**
 * The meta-kernel's {@code text_type} constructor -- the Unicode code point sequence type every
 * other text-shaped atom composes with. Pure constraint values, no parsing/validation behavior --
 * {@code tson-parser}'s {@code TextParser} holds one of these and does the actual reading/writing.
 *
 * <p><b>{@code pattern} is the regex's own source text ({@link String}), not a compiled {@link
 * java.util.regex.Pattern}</b> -- kept a pure, hashable/equatable value the same as every other
 * field here, and consistent with the kernel's own modeling: {@code regex_type} composes with
 * {@code text_type} (§5.7), i.e. a {@code regex} value IS-A piece of text, so the natural
 * representation of a pattern constraint is text too, not a pre-compiled host object. {@code
 * TextParser}/{@code UriParser} compile it at validation time instead of storing the compiled
 * form.
 *
 * <p>Also an {@link Atom} variant (added 2026-07-23, alongside {@code uri_type}/{@code regex_type}
 * below): {@code text => !text_type {}} is a constructor-application instance (§5.5) whose resolved
 * body is exactly {@link #UNCONSTRAINED} -- every field here is {@code Optional}, so an empty
 * {@code {}} body needs no design work beyond what {@link IntegerType} already established for the
 * same shape.
 */
@Typename(name = "text_type")
public record TextType(
        @Field("min_length") Optional<Integer> minLength,
        @Field("max_length") Optional<Integer> maxLength,
        Optional<Integer> length,
        Optional<String> pattern) implements Atom {

    /** {@code text => !text_type {}} -- the unconstrained text type. */
    public static final TextType UNCONSTRAINED =
            new TextType(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
}
