package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;

/**
 * meta.tn's {@code identity_type} constructor: a canonical identity ([TSON-DATA] §2.2.1), one string-class atom.
 * Pure constraint value, with nothing to configure beyond its Part 1 pin -- {@code tson-atom}'s
 * {@code IdentityParser} does the reading, through {@code io.ltr8.tson.base.Identity}. {@code spec} is flat and a
 * bare {@link String}, as {@link HostType}'s is.
 */
@Typename(name = "identity_type")
public record IdentityType(TypeChain chain,
        String spec) implements Atom {
    /** The chain normalised: absent and empty are one list. */
    @Record
    public IdentityType {
        chain = TypeChain.orEmpty(chain);
    }

    /** This body with an empty IS-A chain -- a value no resolver has placed in one. */
    public IdentityType(String spec) {
        this(TypeChain.EMPTY, spec);
    }

    @Override
    public IdentityType withChain(TypeChain chain) {
        return new IdentityType(chain, spec);
    }

    /** {@code identity_type.spec}'s pinned value: [TSON-DATA], whose §2.2.1 defines a canonical identity. */
    public static final String SPEC = "https://tson.io/2026/38/tson-part1-data";

    /** {@code identity => !identity_type {}} -- core.tn's {@code identity}. */
    public static final IdentityType UNCONSTRAINED = new IdentityType(SPEC);
}
