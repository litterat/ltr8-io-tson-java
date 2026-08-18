package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Typename;

import java.util.List;

/**
 * meta.tn1's {@code ipv4_type} constructor (IPv4 address constraint vocabulary, RFC 3986's {@code
 * IPv4address} production): CIDR-text network allow/deny lists. Pure constraint values, no
 * parsing/validation behavior -- {@code tson-compiler}'s {@code Ipv4Parser} does the actual
 * reading/writing, though it does not model {@code within}/{@code excluding} (see its own Javadoc).
 *
 * <p>Same shape as {@link Cidr4Type} minus {@code min_prefix}/{@code max_prefix} (an address, not a
 * network, has no prefix length) -- see {@link Cidr4Type}'s own Javadoc for why {@code spec} is a
 * flat {@link String} (not nested {@link AtomSpecification}, not a {@link java.net.URI}) and why
 * {@code within}/{@code excluding} are bare {@code List<String>} with a defensive compact
 * constructor.
 */
@Typename(name = "ipv4_type")
public record Ipv4Type(String spec, List<String> within, List<String> excluding) implements Atom {

    public Ipv4Type {
        within = within != null ? List.copyOf(within) : List.of();
        excluding = excluding != null ? List.copyOf(excluding) : List.of();
    }

    /** {@code ipv4 => !ipv4_type {}} -- the unconstrained IPv4 address, core.tn1's own {@code !ipv4}. */
    public static final Ipv4Type UNCONSTRAINED =
            new Ipv4Type("https://www.rfc-editor.org/rfc/rfc3986", List.of(), List.of());
}
