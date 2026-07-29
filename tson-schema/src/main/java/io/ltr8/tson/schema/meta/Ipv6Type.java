package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Typename;

import java.util.List;

/**
 * meta.tn1's {@code ipv6_type} constructor (IPv6 address constraint vocabulary, RFC 4291) --
 * {@link Ipv4Type}'s exact IPv6 counterpart, same shape, different RFC citation. Pure constraint
 * values, no parsing/validation behavior -- deliberately no {@code tson-compiler} compiler exists for
 * this atom yet (added as a {@code schema.meta}/{@link Atom} variant only, per explicit user
 * direction, so {@code !ipv6_type {}}/{@code ipv6}'s own resolution succeeds -- not to add real
 * IPv6 validation). See {@link Cidr4Type}'s own Javadoc for why {@code spec} is a flat {@link
 * String} and {@code within}/{@code excluding} are bare {@code List<String>} with a defensive
 * compact constructor.
 */
@Typename(name = "ipv6_type")
public record Ipv6Type(String spec, List<String> within, List<String> excluding) implements Atom {

    public Ipv6Type {
        within = within != null ? List.copyOf(within) : List.of();
        excluding = excluding != null ? List.copyOf(excluding) : List.of();
    }

    /** {@code ipv6 => !ipv6_type {}} -- the unconstrained IPv6 address, core.tn1's own {@code !ipv6}. */
    public static final Ipv6Type UNCONSTRAINED =
            new Ipv6Type("https://www.rfc-editor.org/rfc/rfc4291", List.of(), List.of());
}
