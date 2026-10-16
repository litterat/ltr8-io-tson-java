package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;

import java.util.List;

/**
 * meta.tn's {@code host_type} constructor: a host name, an IPv4 address or an IPv6 address, one string-class
 * atom. Pure constraint value, with nothing to configure beyond its RFC 3987 pin -- {@code tson-atom}'s
 * {@code HostParser} does the reading, through {@code io.ltr8.net.Host}. {@code spec} is flat and a bare
 * {@link String}, as {@link Cidr4Type}'s is.
 */
@Typename(name = "host_type")
public record HostType(TypeChain chain,
        String spec) implements Atom {
    /** The chain normalised: absent and empty are one list. */
    @Record
    public HostType {
        chain = TypeChain.orEmpty(chain);
    }

    /** This body with an empty IS-A chain -- a value no resolver has placed in one. */
    public HostType(String spec) {
        this(TypeChain.EMPTY, spec);
    }

    @Override
    public HostType withChain(TypeChain chain) {
        return new HostType(chain, spec);
    }

    /** {@code host_type.spec}'s pinned value. */
    public static final String SPEC = "https://www.rfc-editor.org/rfc/rfc3987";

    /** {@code host => !host_type {}} -- net.tn's {@code host}. */
    public static final HostType UNCONSTRAINED = new HostType(SPEC);
}
