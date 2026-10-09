package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Typename;

import java.util.ArrayList;
import java.util.List;

/**
 * meta.tn's {@code hostname_type} constructor: a domain name in either label form, judged by IDNA2008. Pure
 * constraint value -- {@code tson-atom}'s {@code HostnameParser} holds one and does the reading, through
 * {@code io.ltr8.net.HostName}.
 *
 * <p>{@code allow_idn} is the one facet: false admits only names with no internationalized label, in either
 * spelling. {@code spec} is pinned to RFC 5890, flat and a bare {@link String}, as {@link Cidr4Type}'s is.
 */
@Typename(name = "hostname_type")
public record HostnameType(String spec, @Field("allow_idn") boolean allowIdn) implements Atom {

    /** {@code hostname_type.spec}'s pinned value. */
    public static final String SPEC = "https://www.rfc-editor.org/rfc/rfc5890";

    /** {@code hostname => !hostname_type {}} -- net.tn's {@code hostname}, internationalized names admitted. */
    public static final HostnameType UNCONSTRAINED = new HostnameType(SPEC, true);

    /**
     * {@inheritDoc}
     *
     * <p>{@link #allowIdn} is a permission, withdrawn but never granted back; {@code spec} is fixed in the
     * schema, so a refinement cannot move it.
     */
    @Override
    public List<String> constraintsCheck(Atom refined) {
        if (!(refined instanceof HostnameType other)) {
            return List.of("refines a hostname with " + refined.getClass().getSimpleName());
        }
        List<String> violations = new ArrayList<>();
        AtomNarrowing.checkOnlyWithdraws(violations, "allow_idn", allowIdn, other.allowIdn);
        return List.copyOf(violations);
    }
}
