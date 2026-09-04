package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Typename;

import java.util.List;

/**
 * meta.tn's {@code ipv6_type} constructor (IPv6 address constraint vocabulary, RFC 4291) --
 * {@link Ipv4Type}'s exact IPv6 counterpart, same shape, different RFC citation. Pure constraint
 * values, no parsing/validation behavior -- {@code tson-compiler}'s {@code Ipv6Parser} does the actual
 * reading/writing, {@code within}/{@code excluding} unmodeled as in {@link Ipv4Type}. See {@link
 * Cidr4Type}'s own Javadoc for why {@code spec} is a flat {@link
 * String} and {@code within}/{@code excluding} are bare {@code List<String>} with a defensive
 * compact constructor.
 */
@Typename(name = "ipv6_type")
public record Ipv6Type(String spec, List<String> within, List<String> excluding) implements Atom {

    public Ipv6Type {
        within = within != null ? List.copyOf(within) : List.of();
        excluding = excluding != null ? List.copyOf(excluding) : List.of();
    }

    /** {@code ipv6 => !ipv6_type {}} -- the unconstrained IPv6 address, core.tn's own {@code !ipv6}. */
    public static final Ipv6Type UNCONSTRAINED =
            new Ipv6Type("https://www.rfc-editor.org/rfc/rfc4291", List.of(), List.of());

    /**
     * {@inheritDoc}
     *
     * <p>Two list facets pulling in opposite directions. {@code within} admits only addresses inside the
     * networks it names, so a refinement narrows by <b>shrinking</b> it to a subset; {@code excluding}
     * removes addresses, so a refinement narrows by <b>growing</b> it. {@code spec} is fixed by the
     * constructor and has nothing to compare.
     *
     * <p><b>Both are compared as written.</b> Deciding that one network sits inside another is arithmetic
     * this module has no parser for, so a refinement naming a strictly smaller network than the source's is
     * refused here even though it narrows. That is the conservative direction -- it refuses a legal
     * refinement rather than admitting an illegal one -- and it is what a stated relation would replace.
     */
    @Override
    public java.util.List<String> constraintsCheck(Atom refined) {
        if (!(refined instanceof Ipv6Type other)) {
            return java.util.List.of("refines an ipv6 with " + refined.getClass().getSimpleName());
        }
        java.util.List<String> violations = new java.util.ArrayList<>();
        AtomNarrowing.checkSubset(violations, "within", within, other.within);
        AtomNarrowing.checkSuperset(violations, "excluding", excluding, other.excluding);
        return java.util.List.copyOf(violations);
    }
}
