package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Typename;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The meta-kernel's {@code uri_type} constructor (§5.5's {@code uri} atom), composing {@code
 * text_type}'s {@code min_length}/{@code max_length}/{@code pattern}, {@code atom_specification}'s
 * {@code spec}, and its own {@code scheme} field (meta.tn1: {@code uri_type => ~text_type &
 * atom_specification & { spec: = "https://www.rfc-editor.org/rfc/rfc3986" scheme: text? } }). Pure
 * constraint values, no parsing/validation behavior -- {@code tson-compiler}'s {@code UriParser}
 * holds one of these and does the actual reading/writing.
 *
 * <p>{@code pattern} is the regex's own source text ({@link String}), not a compiled {@link
 * java.util.regex.Pattern} -- see {@link TextType#pattern()}'s own Javadoc for why.
 *
 * <p>Also an {@link Atom} variant (added 2026-07-23, alongside {@code text_type} above/{@code
 * regex_type} below): {@code uri => !uri_type {}} is a constructor-application instance (§5.5)
 * whose resolved body is exactly {@link #UNCONSTRAINED}.
 */
@Typename(name = "uri_type")
public record UriType(
        @Field("min_length") Optional<Integer> minLength,
        @Field("max_length") Optional<Integer> maxLength,
        Optional<String> pattern,
        AtomSpecification specification,
        Optional<String> scheme) implements Atom {

    private static final URI RFC_3986 = URI.create("https://www.rfc-editor.org/rfc/rfc3986");

    /** {@code uri => !uri_type {}} -- the unconstrained URI, §5.5's {@code !uri}. */
    public static final UriType UNCONSTRAINED = new UriType(
            Optional.empty(), Optional.empty(), Optional.empty(), new AtomSpecification(RFC_3986), Optional.empty());

    /**
     * {@inheritDoc}
     *
     * <p>Only the length bounds are ordered. {@link #pattern} is undecidable here for the reason
     * {@link TextType#constraintsCheck} gives; {@link #scheme} is a selector ({@link ComplexType});
     * {@link #specification} is {@code REQUIRED_FIXED} in the schema, so a refinement cannot move it
     * in the first place.
     */
    @Override
    public List<String> constraintsCheck(Atom refined) {
        if (!(refined instanceof UriType other)) {
            return List.of("refines a uri with " + refined.getClass().getSimpleName());
        }
        List<String> violations = new ArrayList<>();
        AtomNarrowing.checkAtLeast(violations, "min_length", minLength, other.minLength);
        AtomNarrowing.checkAtMost(violations, "max_length", maxLength, other.maxLength);
        return List.copyOf(violations);
    }
}
