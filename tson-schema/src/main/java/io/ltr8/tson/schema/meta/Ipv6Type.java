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
     * <p><b>Both are compared as written</b>, by entry rather than by containment, so a refinement naming a
     * strictly smaller network than the source's is refused here even though it narrows. That is the
     * conservative direction -- it refuses a legal refinement rather than admitting an illegal one -- and it
     * is what a stated relation would replace. The containment arithmetic to decide it properly does exist
     * now ({@code schema.atom.CidrNetwork}); what is missing is the spec rule saying which way a set facet
     * narrows, which {@code SPEC-FEEDBACK.md} #29 asks for.
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

    /**
     * {@inheritDoc}
     *
     * <p>Every entry of {@code within} and {@code excluding} must be a network. The facets are typed
     * {@code [value]} in meta.tn -- they list networks, and meta declares no network instance to type them
     * by, core.tn doing that and core importing meta -- so any token satisfies the vocabulary and a missing
     * prefix length or a transposed octet reaches here as ordinary text.
     *
     * <p>It is checked here rather than anywhere downstream because it is this family's rule: a malformed
     * entry makes the facet unreadable, which is the same kind of defect as a floor above a ceiling. Every
     * bad entry is named rather than the first, a list being written in one go.
     *
     * <p>The two are then judged <b>together</b>: an {@code excluding} list covering every network {@code
     * within} permits admits no address, and the emptiness question is exactly what this check is for. Cover
     * over a prefix tree is decidable exactly -- see {@code AtomCoherence.checkAdmitsAValue} -- so nothing is
     * approximated and no pair is left undecided.
     */
    @Override
    public java.util.List<String> coherenceCheck() {
        java.util.List<String> violations = new java.util.ArrayList<>();
        AtomCoherence.checkNetworks(violations, "within", within, 128);
        AtomCoherence.checkNetworks(violations, "excluding", excluding, 128);
        AtomCoherence.checkAdmitsAValue(violations, "address", within, excluding, 128);
        return java.util.List.copyOf(violations);
    }
}
