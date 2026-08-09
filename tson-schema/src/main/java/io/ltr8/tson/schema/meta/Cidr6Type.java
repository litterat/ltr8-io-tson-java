package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Typename;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * meta.tn1's {@code cidr6_type} constructor (IPv6-network constraint vocabulary, RFC 4291) --
 * {@link Cidr4Type}'s exact IPv6 counterpart, same shape, different RFC citation and prefix-length
 * family range (0-128 instead of 0-32, not enforced here either way). See {@link Cidr4Type}'s own
 * Javadoc for why {@code spec} is a flat {@link String} rather than nested {@link
 * AtomSpecification} or a {@link java.net.URI}, why {@code within}/{@code excluding} are bare
 * {@code List<String>} with a defensive compact constructor, and why no compiler exists for this atom
 * yet.
 */
@Typename(name = "cidr6_type")
public record Cidr6Type(String spec, @Field("min_prefix") Optional<Integer> minPrefix,
                         @Field("max_prefix") Optional<Integer> maxPrefix,
                         List<String> within, List<String> excluding) implements Atom {

    public Cidr6Type {
        within = within != null ? List.copyOf(within) : List.of();
        excluding = excluding != null ? List.copyOf(excluding) : List.of();
    }

    /** {@code cidr6 => !cidr6_type {}} -- the unconstrained IPv6 network, core.tn1's own {@code !cidr6}. */
    public static final Cidr6Type UNCONSTRAINED = new Cidr6Type(
            "https://www.rfc-editor.org/rfc/rfc4291", Optional.empty(), Optional.empty(), List.of(), List.of());

    /** {@inheritDoc} <p>The IPv6 twin of {@link Cidr4Type#constraintsCheck}, including its {@code excluding} gap. */
    @Override
    public List<String> constraintsCheck(Atom refined) {
        if (!(refined instanceof Cidr6Type other)) {
            return List.of("refines a cidr6 with " + refined.getClass().getSimpleName());
        }
        List<String> violations = new ArrayList<>();
        AtomNarrowing.checkAtLeast(violations, "min_prefix", minPrefix, other.minPrefix);
        AtomNarrowing.checkAtMost(violations, "max_prefix", maxPrefix, other.maxPrefix);
        AtomNarrowing.checkSubset(violations, "within", within, other.within);
        return List.copyOf(violations);
    }
}
