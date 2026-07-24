package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Typename;

/**
 * meta.tn1's {@code mac_type} constructor (EUI-48 MAC address, RFC 9542) -- deliberately bare
 * beyond the RFC pin (I/G-/U/L-bit predicates and OUI vendor prefixes were considered and rejected
 * as niche, per meta.tn1's own doc comment). Pure constraint value, no parsing/validation behavior
 * -- deliberately no {@code tson-parser} parser exists for this atom yet (added as a {@code
 * schema.meta}/{@link Atom} variant only, per explicit user direction, so {@code !mac_type {}}/
 * {@code mac}'s own resolution succeeds -- not to add real MAC-address validation).
 *
 * <p>{@code spec} is a bare {@link String}, not nested inside {@link AtomSpecification} or typed
 * as a {@link java.net.URI} -- see {@link Cidr4Type}'s own Javadoc for why.
 */
@Typename(name = "mac_type")
public record MacType(String spec) implements Atom {

    /** {@code mac => !mac_type {}} -- the unconstrained MAC address, core.tn1's own {@code !mac}. */
    public static final MacType UNCONSTRAINED = new MacType("https://www.rfc-editor.org/rfc/rfc9542");
}
