package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Typename;

import java.net.URI;

/**
 * The meta-kernel's {@code regex_type} constructor (Part 2 §5.7: {@code regex_type => ~text_type &
 * atom_specification & { spec: = "https://www.rfc-editor.org/rfc/rfc9485" } }) -- composes {@code
 * text_type}'s constraint vocabulary (held here as a {@link TextType}, not flattened field-by-field
 * the way {@link UriType} flattens it -- {@code regex_type} declares no field of its own beyond
 * these two composed values, so there's nothing to flatten *into*) with {@code atom_specification}'s
 * {@code spec} (held as an {@link AtomSpecification}, fixed to RFC 9485 -- the I-Regexp
 * specification -- distinct from {@code uri_type}'s own RFC 3986 citation via the same mixin, see
 * {@link UriType}). Pure constraint values, no parsing/validation behavior -- {@code tson-parser}'s
 * {@code RegexParser} holds one of these and does the actual reading/writing.
 *
 * <p>Also an {@link Atom} variant (added 2026-07-23, alongside {@code text_type}/{@code uri_type}
 * above): {@code regex => !regex_type {}} is a constructor-application instance (§5.5) whose
 * resolved body is exactly {@link #UNCONSTRAINED}.
 */
@Typename(name = "regex_type")
public record RegexType(TextType constraints, AtomSpecification specification) implements Atom {

    private static final URI RFC_9485 = URI.create("https://www.rfc-editor.org/rfc/rfc9485");

    /** {@code regex => !regex_type {}} -- the unconstrained regex type. */
    public static final RegexType UNCONSTRAINED = new RegexType(TextType.UNCONSTRAINED, new AtomSpecification(RFC_9485));
}
