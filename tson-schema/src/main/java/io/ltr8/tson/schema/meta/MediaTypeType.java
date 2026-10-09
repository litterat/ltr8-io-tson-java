package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Typename;
import io.ltr8.net.MediaType;
import io.ltr8.net.MediaTypeSyntaxException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * meta.tn's {@code media_type_type} constructor: a media type (RFC 6838) and its parameters (RFC 9110 §8.3.1). Pure
 * constraint value -- {@code tson-atom}'s {@code MediaTypeParser} holds one and does the reading, through
 * {@code io.ltr8.net.MediaType}.
 *
 * <p>The facets: {@code allow_parameters} admits parameters (false by default); {@code types} lists the top-level
 * types admitted; {@code suffixes} lists RFC 6839 structured suffixes, a suffix admitting a subtype ending
 * {@code +suffix} and the subtype that is the suffix itself. Names compare without ASCII case. {@code spec} is
 * pinned to RFC 6838, flat and a bare {@link String}, as {@link Cidr4Type}'s is.
 */
@Typename(name = "media_type_type")
public record MediaTypeType(String spec, @Field("allow_parameters") boolean allowParameters,
                            Optional<List<String>> types, Optional<List<String>> suffixes) implements Atom {

    public MediaTypeType {
        types = types.map(List::copyOf);
        suffixes = suffixes.map(List::copyOf);
    }

    /** {@code media_type_type.spec}'s pinned value. */
    public static final String SPEC = "https://www.rfc-editor.org/rfc/rfc6838";

    /** {@code media_type => !media_type_type {}} -- net.tn's {@code media_type}, without parameters. */
    public static final MediaTypeType UNCONSTRAINED =
            new MediaTypeType(SPEC, false, Optional.empty(), Optional.empty());

    /**
     * {@inheritDoc}
     *
     * <p>{@link #allowParameters} is a permission, withdrawn but never granted back; {@link #types} and
     * {@link #suffixes} are member sets and only shrink, compared without case.
     */
    @Override
    public List<String> constraintsCheck(Atom refined) {
        if (!(refined instanceof MediaTypeType other)) {
            return List.of("refines a media_type with " + refined.getClass().getSimpleName());
        }
        List<String> violations = new ArrayList<>();
        AtomNarrowing.checkOnlyWithdraws(violations, "allow_parameters", allowParameters, other.allowParameters);
        AtomNarrowing.checkSubset(violations, "types", types.orElse(List.of()), other.types.orElse(List.of()),
                String::equalsIgnoreCase);
        AtomNarrowing.checkSubset(violations, "suffixes", suffixes.orElse(List.of()),
                other.suffixes.orElse(List.of()), String::equalsIgnoreCase);
        return List.copyOf(violations);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Every entry of {@code types} and {@code suffixes} must be an RFC 6838 name, and neither may be empty: an
     * empty list admits no media type at all.
     */
    @Override
    public List<String> coherenceCheck() {
        List<String> violations = new ArrayList<>();
        check(violations, "types", types, name -> name + "/x");
        check(violations, "suffixes", suffixes, name -> "x/x+" + name);
        return List.copyOf(violations);
    }

    private static void check(List<String> out, String facet, Optional<List<String>> names,
                              java.util.function.UnaryOperator<String> asMediaType) {
        if (names.isEmpty()) {
            return;
        }
        if (names.get().isEmpty()) {
            out.add(facet + " is empty, so no media type is admitted");
        }
        for (String name : names.get()) {
            try {
                MediaType.parse(asMediaType.apply(name));
                if (name.indexOf('+') >= 0 && facet.equals("suffixes")) {
                    out.add("suffixes holds '" + name + "', and a suffix is the text after a subtype's last '+'");
                }
            } catch (MediaTypeSyntaxException e) {
                out.add(facet + " holds '" + name + "', which is not an RFC 6838 name");
            }
        }
    }
}
