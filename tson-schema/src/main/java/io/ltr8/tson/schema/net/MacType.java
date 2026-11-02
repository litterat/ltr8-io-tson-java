package io.ltr8.tson.schema.net;

import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.Atom;
import io.ltr8.tson.schema.meta.TypeChain;

import java.util.List;

/**
 * net.tn's {@code net.mac_type} constructor (EUI-48 MAC address, RFC 9542) -- deliberately bare
 * beyond the RFC pin (I/G-/U/L-bit predicates and OUI vendor prefixes were considered and rejected
 * as niche, per net.tn's own doc comment). Pure constraint value, no parsing/validation behavior
 * -- {@code tson-compiler}'s {@code MacParser} holds one of these and does the actual
 * reading/writing.
 *
 * <p>{@code spec} is flat and a bare {@link String}, never a {@link java.net.URI} -- see {@link
 * Cidr4Type}'s own Javadoc for why.
 */
@Typename(name = "net.mac_type")
public record MacType(TypeChain chain,
        String spec) implements Atom {
    /** The chain normalised: absent and empty are one list. */
    @Record
    public MacType {
        chain = TypeChain.orEmpty(chain);
    }

    /** This body with an empty IS-A chain -- a value no resolver has placed in one. */
    public MacType(String spec) {
        this(TypeChain.EMPTY, spec);
    }

    @Override
    public MacType withChain(TypeChain chain) {
        return new MacType(chain, spec);
    }

    /** {@code mac => !net.mac_type {}} -- the unconstrained MAC address, net.tn's own {@code !mac}. */
    public static final MacType UNCONSTRAINED = new MacType("https://www.rfc-editor.org/rfc/rfc9542");
}
