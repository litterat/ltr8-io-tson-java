package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Typename;

import java.util.List;
import java.util.Optional;

/**
 * meta.tn's {@code iri_type} constructor (§5.5's {@code iri} and {@code iri_reference} atoms): {@code
 * text_type}'s length and pattern facets, {@code atom_specification}'s {@code spec} pinned to RFC 3987, and
 * {@code uri_type}'s own {@code schemes}, {@code allow_relative} and {@code allow_fragment}. Pure constraint
 * values -- {@code tson-atom}'s {@code IriParser} holds one of these and does the reading.
 *
 * <p>A separate family from {@link UriType} because the grammar is a different RFC's: RFC 3987 admits
 * characters beyond US-ASCII, which RFC 3986 does not. The facets are {@code uri_type}'s and mean the same,
 * so their comparison rules are {@link UriType}'s, reached through {@link #uriFacets()} rather than restated.
 * Every field is flat, mirroring the resolved shape -- see {@link UriType}.
 *
 * <p>{@code iri_reference => !iri_type {}} resolves to exactly {@link #REFERENCE}, and {@code iri =>
 * !iri_reference ^ { allow_relative: false }} to {@link #IRI}.
 */
@Typename(name = "iri_type")
public record IriType(String spec, @Field("min_length") Optional<Integer> minLength,
                      @Field("max_length") Optional<Integer> maxLength,
                      Optional<Integer> length, Optional<String> pattern,
                      Optional<List<String>> members, Optional<List<String>> schemes,
                      @Field("allow_relative") boolean allowRelative,
                      @Field("allow_fragment") boolean allowFragment) implements Atom {

    /** RFC 3987, the one {@code spec} every {@code iri_type} carries. */
    public static final String SPEC = "https://www.rfc-editor.org/rfc/rfc3987";

    /** {@code iri_reference => !iri_type {}} -- §5.5's {@code !iri_reference}, an IRI or a relative reference. */
    public static final IriType REFERENCE = new IriType(SPEC, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), true, true);

    /** {@code iri => !iri_reference ^ { allow_relative: false }} -- §5.5's {@code !iri}, which has a scheme. */
    public static final IriType IRI = new IriType(SPEC, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false, true);

    /** The same facets as the {@link UriType} that owns their rules; {@code spec} is not among them. */
    public UriType uriFacets() {
        return new UriType(UriType.SPEC, minLength, maxLength, length, pattern, members, schemes,
                allowRelative, allowFragment);
    }

    /** {@inheritDoc} <p>{@code uri_type}'s rules, facet for facet ({@link UriType#constraintsCheck}). */
    @Override
    public List<String> constraintsCheck(Atom refined) {
        if (!(refined instanceof IriType other)) {
            return List.of("refines an iri with " + refined.getClass().getSimpleName());
        }
        return uriFacets().constraintsCheck(other.uriFacets());
    }

    /** {@inheritDoc} <p>{@code uri_type}'s rule ({@link UriType#coherenceCheck}). */
    @Override
    public List<String> coherenceCheck() {
        return uriFacets().coherenceCheck();
    }
}
