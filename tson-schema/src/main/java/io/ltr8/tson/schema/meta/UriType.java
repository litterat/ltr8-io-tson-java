package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Typename;
import io.ltr8.tson.base.unicode.Normalization;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The meta-kernel's {@code uri_type} constructor (§5.5's {@code uri} and {@code uri_reference} atoms):
 * {@code text_type}'s length and pattern facets, {@code atom_specification}'s {@code spec} pinned to RFC
 * 3986, and its own {@code schemes}, {@code allow_relative} and {@code allow_fragment} fields. Pure constraint values, no
 * parsing/validation behavior -- {@code tson-atom}'s {@code UriParser} holds one of these and does the
 * actual reading/writing.
 *
 * <p>{@code allow_relative} is RFC 3986's own split: left at its default the value space is a
 * URI-reference (§4.1), a URI or a relative reference; withdrawn it is a URI (§3), which has a scheme.
 * {@code allow_fragment} withdrawn refuses a fragment (§3.5), so the two withdrawn together are an
 * absolute-URI (§4.3). {@code schemes} is a member set compared case-insensitively, as §3.1 compares a
 * scheme, and a value with no scheme is outside it.
 *
 * <p><b>Every field is flat, mirroring the resolved shape rather than the composition that produced
 * it</b> -- composition always flattens (§5.8), so an instance's wire record carries every facet side by
 * side with no sub-record anywhere, and a component nesting one under a name the wire doesn't have
 * receives nothing at all. See {@link RegexType}, declared by the same composition, for the longer form
 * of this note; {@link EmailType} and {@link Cidr4Type} are the same shape again.
 *
 * <p>{@code spec} is a bare {@link String}, not a {@link java.net.URI}, even though it holds one: the
 * schema writes it as an untyped, unannotated quoted value ({@code spec: = "https://..."}), and {@code
 * AtomBinder} converts a string into {@code URI} only through the built-in-vocabulary type-ref path
 * ({@code !uri "..."}), never the untyped one this field goes through. {@code pattern} is the regex's
 * own source text ({@link String}), not a compiled {@link java.util.regex.Pattern} -- see {@link
 * TextType#pattern()}.
 *
 * <p>{@code uri_reference => !uri_type {}} resolves to exactly {@link #REFERENCE}, and {@code uri =>
 * !uri_reference ^ { allow_relative: false }} to {@link #URI}.
 */
@Typename(name = "uri_type")
public record UriType(String spec, @Field("min_length") Optional<Integer> minLength,
                      @Field("max_length") Optional<Integer> maxLength,
                      Optional<Integer> length, Optional<String> pattern,
                      Optional<List<String>> members, Optional<List<String>> schemes,
                      @Field("allow_relative") boolean allowRelative,
                      @Field("allow_fragment") boolean allowFragment,
                      Normalization normalization) implements Atom, TextFamily {

    /** RFC 3986, the one {@code spec} every {@code uri_type} carries. */
    public static final String SPEC = "https://www.rfc-editor.org/rfc/rfc3986";

    /** {@code uri_reference => !uri_type {}} -- §5.5's {@code !uri_reference}, a URI or a relative reference. */
    public static final UriType REFERENCE = new UriType(SPEC, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), true, true, Normalization.NONE);

    /** {@code uri => !uri_reference ^ { allow_relative: false }} -- §5.5's {@code !uri}, which has a scheme. */
    public static final UriType URI = new UriType(SPEC, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false, true, Normalization.NONE);

    /** Whether {@code scheme} is one {@link #schemes} admits -- §3.1's case-insensitive comparison. */
    public boolean admitsScheme(String scheme) {
        return schemes.isEmpty() || scheme != null && schemes.get().stream().anyMatch(scheme::equalsIgnoreCase);
    }

    /** The {@code text_type} facets this composes, as the {@link TextType} that owns their comparison rules. */
    public TextType textConstraints() {
        return new TextType(minLength, maxLength, length, pattern, members, normalization);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The length facets narrow as {@link TextType}'s own rule says, {@link #length} pinning both ends.
     * {@link #pattern} is undecidable here for the reason {@link TextType#constraintsCheck} gives;
     * {@code spec} is fixed in the schema, so a refinement cannot move it in the first place.
     * {@link #schemes} is a member set and may only shrink, compared as §3.1 compares a scheme;
     * {@link #allowRelative} and {@link #allowFragment} are permissions, withdrawn but never granted back.
     */
    @Override
    public List<String> constraintsCheck(Atom refined) {
        if (!(refined instanceof UriType other)) {
            return List.of("refines a uri with " + refined.getClass().getSimpleName());
        }
        List<String> violations = new ArrayList<>(textConstraints().constraintsCheck(other.textConstraints()));
        AtomNarrowing.checkSubset(violations, "schemes", schemes.orElse(List.of()),
                other.schemes.orElse(List.of()), String::equalsIgnoreCase);
        AtomNarrowing.checkOnlyWithdraws(violations, "allow_relative", allowRelative, other.allowRelative);
        AtomNarrowing.checkOnlyWithdraws(violations, "allow_fragment", allowFragment, other.allowFragment);
        return List.copyOf(violations);
    }

    /** {@inheritDoc} <p>The length facets this composes, judged by {@link TextType#coherenceCheck} that owns them. */
    @Override
    public List<String> coherenceCheck() {
        return textConstraints().coherenceCheck();
    }
}
