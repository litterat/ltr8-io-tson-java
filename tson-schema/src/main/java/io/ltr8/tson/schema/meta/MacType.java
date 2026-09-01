package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Typename;

/**
 * meta.tn's {@code mac_type} constructor (EUI-48 MAC address, RFC 9542) -- deliberately bare
 * beyond the RFC pin (I/G-/U/L-bit predicates and OUI vendor prefixes were considered and rejected
 * as niche, per meta.tn's own doc comment). Pure constraint value, no parsing/validation behavior
 * -- {@code tson-compiler}'s {@code MacParser} holds one of these and does the actual
 * reading/writing.
 *
 * <p>{@code spec} is flat and a bare {@link String}, never a {@link java.net.URI} -- see {@link
 * Cidr4Type}'s own Javadoc for why.
 */
@Typename(name = "mac_type")
public record MacType(String spec) implements Atom {

    /** {@code mac => !mac_type {}} -- the unconstrained MAC address, core.tn's own {@code !mac}. */
    public static final MacType UNCONSTRAINED = new MacType("https://www.rfc-editor.org/rfc/rfc9542");
}
