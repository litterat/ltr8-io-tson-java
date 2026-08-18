package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Typename;

import java.util.List;
import java.util.Optional;

/**
 * The meta-kernel's {@code uri_type} constructor (§5.5's {@code uri} atom): {@code text_type}'s length
 * and pattern facets, {@code atom_specification}'s {@code spec} pinned to RFC 3986, and its own {@code
 * scheme} field (meta-kernel: {@code uri_type => ~text_type & atom_specification & { spec: =
 * "https://www.rfc-editor.org/rfc/rfc3986" scheme: text? } }). Pure constraint values, no
 * parsing/validation behavior -- {@code tson-compiler}'s {@code UriParser} holds one of these and does
 * the actual reading/writing.
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
 * <p>{@code uri => !uri_type {}} is a constructor-application instance (§5.5) whose resolved body is
 * exactly {@link #UNCONSTRAINED}.
 */
@Typename(name = "uri_type")
public record UriType(String spec, @Field("min_length") Optional<Integer> minLength,
                      @Field("max_length") Optional<Integer> maxLength,
                      Optional<Integer> length, Optional<String> pattern,
                      Optional<String> scheme) implements Atom {

    /** {@code uri => !uri_type {}} -- the unconstrained URI, §5.5's {@code !uri}. */
    public static final UriType UNCONSTRAINED = new UriType(
            "https://www.rfc-editor.org/rfc/rfc3986", Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty());

    /** The {@code text_type} facets this composes, as the {@link TextType} that owns their comparison rules. */
    public TextType textConstraints() {
        return new TextType(minLength, maxLength, length, pattern);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The length facets narrow as {@link TextType}'s own rule says, {@link #length} pinning both ends.
     * {@link #pattern} is undecidable here for the reason {@link TextType#constraintsCheck} gives;
     * {@link #scheme} is a selector ({@link ComplexType}); {@code spec} is {@code REQUIRED_FIXED} in the
     * schema, so a refinement cannot move it in the first place.
     */
    @Override
    public List<String> constraintsCheck(Atom refined) {
        if (!(refined instanceof UriType other)) {
            return List.of("refines a uri with " + refined.getClass().getSimpleName());
        }
        return textConstraints().constraintsCheck(other.textConstraints());
    }
}
