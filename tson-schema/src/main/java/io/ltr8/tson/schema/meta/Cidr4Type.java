package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Typename;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * meta.tn's {@code cidr4_type} constructor (IPv4-network constraint vocabulary, RFC 4632):
 * prefix-length bounds plus CIDR-text network lists. Pure constraint values, no parsing/validation
 * behavior -- {@code tson-compiler}'s {@code Cidr4Parser} holds one of these and does the actual
 * reading/writing, applying {@code min_prefix}/{@code max_prefix} but not {@code within}/{@code
 * excluding} (see its own Javadoc).
 *
 * <p><b>{@code spec} is flat, and a bare {@link String}</b> -- two separate requirements, both confirmed
 * empirically, not assumed. <b>Flat</b>, because {@code atom_specification}'s own {@code spec} field
 * composes into {@code cidr4_type} flat (composition always flattens, §5.8) and {@code tson-compiler}'s
 * compiled {@code Record*Reader} fills a field, including a REQUIRED_FIXED field's schema-composed
 * default, under its own schema field name -- a component nesting it under a name the wire doesn't carry
 * receives nothing at all. <b>A {@link String}</b>, because the value arrives untyped: the schema
 * modifier is just {@code spec: = "https://..."}, with no {@code !uri} type-ref, and {@code AtomBinder}
 * converts a string into {@code java.net.URI} only via the built-in-vocabulary type-ref path ({@code !uri
 * "..."}), never the untyped path this field goes through. That second point is the same reason {@code
 * TextType.pattern} is an {@code Optional<String>} rather than a compiled {@code Pattern}. Every atom
 * body in this package citing an external document follows both rules.
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

    /** {@code cidr4 => !cidr4_type {}} -- the unconstrained IPv4 network, core.tn's own {@code !cidr4}. */
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
        AtomNarrowing.checkSuperset(violations, "excluding", excluding, other.excluding);
        return List.copyOf(violations);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The sharpest case in the package, because the schema names the rule outright rather than
     * leaving it to be inferred: meta.tn's own {@code @doc} for this constructor says the prefix
     * bounds narrow "within the family range 0-32" and that "bounds outside that range are invalid at
     * the schema level". So both facets are judged twice -- against each other, and against the 32
     * bits an IPv4 address has.
     *
     * <p>{@link #within}/{@link #excluding} are left out, as they are in {@link #constraintsCheck}
     * and for the same reason: deciding that a {@code within} list and an {@code excluding} list
     * between them admit no network is CIDR containment arithmetic, which this family has no parser
     * for. A malformed entry in either list is separately not this check's business -- it is data the
     * constructor's own vocabulary already validated.
     */
    @Override
    public List<String> coherenceCheck() {
        List<String> violations = new ArrayList<>();
        AtomCoherence.checkWithin(violations, "min_prefix", minPrefix, 0, PREFIX_BITS);
        AtomCoherence.checkWithin(violations, "max_prefix", maxPrefix, 0, PREFIX_BITS);
        AtomCoherence.checkOrdered(violations, "min_prefix", minPrefix, "max_prefix", maxPrefix);
        return List.copyOf(violations);
    }

    /** An IPv4 address's width, and so the inclusive ceiling on any prefix length. */
    private static final int PREFIX_BITS = 32;
}
