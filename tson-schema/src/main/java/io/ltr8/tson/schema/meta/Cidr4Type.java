package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Typename;

import java.util.List;
import java.util.Optional;

/**
 * meta.tn1's {@code cidr4_type} constructor (IPv4-network constraint vocabulary, RFC 4632):
 * prefix-length bounds plus CIDR-text network lists. Pure constraint values, no parsing/validation
 * behavior -- deliberately no {@code tson-parser} parser exists for this atom yet (added as a
 * {@code schema.meta}/{@link Atom} variant only, per explicit user direction, so {@code
 * !cidr4_type {}}/{@code cidr4}'s own resolution succeeds -- not to add real CIDR validation).
 *
 * <p>{@code spec} is a bare {@link String}, not nested inside {@link AtomSpecification} the way
 * {@link UriType}/{@link RegexType} keep it, and not a {@link java.net.URI} either -- two separate
 * corrections from an initial attempt, both confirmed empirically, not assumed: (1) {@code
 * PositionalForm}'s schema-composed-default filling (added the same session) injects a
 * REQUIRED_FIXED field's value flat, under its own schema field name, and {@code
 * atom_specification}'s own {@code spec} field composes into {@code cidr4_type} flat too
 * (composition always flattens, §5.8) -- {@code UriType}/{@code RegexType}'s own nested {@code
 * specification: AtomSpecification} field predates this mechanism and still doesn't bind correctly
 * for exactly this reason (see {@code MetaKernelParser}'s own Javadoc), not retrofitted here to
 * avoid a breaking change to those two already-tested classes; (2) a bare, untyped string value
 * (no {@code !uri} type-ref -- the schema modifier is just {@code spec: = "https://..."}, no
 * annotation) can't bind directly into a {@code java.net.URI}-typed field at all -- {@code
 * AtomBinder} only converts a recognized string into {@code URI} via the built-in-vocabulary
 * type-ref path ({@code !uri "..."}), not the untyped path this field actually goes through. This
 * is the exact same reason {@code TextType}/{@code UriType.pattern} are {@code Optional<String>},
 * not a compiled {@code Pattern} -- same class of gap, same fix.
 *
 * <p>{@code within}/{@code excluding} are the schema's own {@code [value]?} (an optional array of
 * the kernel's untyped {@code value}) -- modeled as a bare, always-present {@code List<String>}
 * (never {@code Optional<List<T>>}, which {@code tson-bind} doesn't support -- the same reason
 * {@code TypeDefinition.supertypes}/{@code parameters} are bare lists too), carrying each CIDR-text
 * network exactly as written, uninterpreted. Needs a defensive compact constructor -- confirmed
 * empirically, not assumed: a bare {@code List} field left absent from the wire data binds as Java
 * {@code null} (`tson-bind` has no auto-defaulting for a missing collection field), so the
 * constructor null-coalesces to {@link List#of()}.
 */
@Typename(name = "cidr4_type")
public record Cidr4Type(String spec, @Field("min_prefix") Optional<Integer> minPrefix,
                         @Field("max_prefix") Optional<Integer> maxPrefix,
                         List<String> within, List<String> excluding) implements Atom {

    public Cidr4Type {
        within = within != null ? List.copyOf(within) : List.of();
        excluding = excluding != null ? List.copyOf(excluding) : List.of();
    }

    /** {@code cidr4 => !cidr4_type {}} -- the unconstrained IPv4 network, core.tn1's own {@code !cidr4}. */
    public static final Cidr4Type UNCONSTRAINED = new Cidr4Type(
            "https://www.rfc-editor.org/rfc/rfc4632", Optional.empty(), Optional.empty(), List.of(), List.of());
}
