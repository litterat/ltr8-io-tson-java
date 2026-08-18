package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Typename;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * meta.tn1's {@code cidr4_type} constructor (IPv4-network constraint vocabulary, RFC 4632):
 * prefix-length bounds plus CIDR-text network lists. Pure constraint values, no parsing/validation
 * behavior -- {@code tson-compiler}'s {@code Cidr4Parser} holds one of these and does the actual
 * reading/writing, applying {@code min_prefix}/{@code max_prefix} but not {@code within}/{@code
 * excluding} (see its own Javadoc).
 *
 * <p>{@code spec} is a bare {@link String}, not nested inside {@link AtomSpecification} the way
 * {@link UriType} keeps it, and not a {@link java.net.URI} either -- two separate
 * corrections from an initial attempt, both confirmed empirically, not assumed: (1) {@code
 * tson-compiler}'s compiled {@code Record*Reader} injects a REQUIRED_FIXED field's schema-composed
 * default value flat, under its own schema field name, and {@code
 * atom_specification}'s own {@code spec} field composes into {@code cidr4_type} flat too
 * (composition always flattens, §5.8) -- {@link UriType}'s own nested {@code specification:
 * AtomSpecification} field is the one place that still doesn't bind for exactly this reason; (2) a bare,
 * untyped string value
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

    /**
     * {@inheritDoc}
     *
     * <p>A longer prefix is a smaller network, so the prefix bounds narrow the ordinary way: {@code
     * min_prefix} may only rise, {@code max_prefix} may only fall. {@link #within} may only shrink,
     * each entry being a network the value is permitted to fall inside.
     *
     * <p>{@link #excluding} is left unchecked: adding an exclusion narrows and removing one widens,
     * the opposite direction from every other set facet here, and deciding it properly means
     * comparing networks for containment rather than entries for membership -- {@code 10.0.0.0/8}
     * subsumes an excluded {@code 10.1.0.0/16} without either list mentioning the other. That
     * belongs with a real CIDR parser, which this family does not have.
     */
    @Override
    public List<String> constraintsCheck(Atom refined) {
        if (!(refined instanceof Cidr4Type other)) {
            return List.of("refines a cidr4 with " + refined.getClass().getSimpleName());
        }
        List<String> violations = new ArrayList<>();
        AtomNarrowing.checkAtLeast(violations, "min_prefix", minPrefix, other.minPrefix);
        AtomNarrowing.checkAtMost(violations, "max_prefix", maxPrefix, other.maxPrefix);
        AtomNarrowing.checkSubset(violations, "within", within, other.within);
        return List.copyOf(violations);
    }
}
