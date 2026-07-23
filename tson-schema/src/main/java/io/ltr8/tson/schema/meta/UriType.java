package io.ltr8.tson.schema.meta;

import java.net.URI;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The meta-kernel's {@code uri_type} constructor (§5.5's {@code uri} atom), composing {@code
 * text_type}'s {@code min_length}/{@code max_length}/{@code pattern}, {@code atom_specification}'s
 * {@code spec}, and its own {@code scheme} field (meta.tn1: {@code uri_type => ~text_type &
 * atom_specification & { spec: = "https://www.rfc-editor.org/rfc/rfc3986" scheme: text? } }). Pure
 * constraint values, no parsing/validation behavior -- {@code tson-parser}'s {@code UriParser}
 * holds one of these and does the actual reading/writing.
 */
public record UriType(
        Optional<Integer> minLength,
        Optional<Integer> maxLength,
        Optional<Pattern> pattern,
        AtomSpecification specification,
        Optional<String> scheme) {

    private static final URI RFC_3986 = URI.create("https://www.rfc-editor.org/rfc/rfc3986");

    /** {@code uri => !uri_type {}} -- the unconstrained URI, §5.5's {@code !uri}. */
    public static final UriType UNCONSTRAINED = new UriType(
            Optional.empty(), Optional.empty(), Optional.empty(), new AtomSpecification(RFC_3986), Optional.empty());
}
