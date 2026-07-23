package io.ltr8.tson.schema.meta;

import java.util.Optional;

/**
 * The meta-kernel's {@code uuid_type} constructor (§5.5's {@code uuid} atom, RFC 9562). Pure
 * constraint values, no parsing/validation behavior -- {@code tson-parser}'s {@code UuidParser}
 * holds one of these and does the actual reading/writing.
 */
public record UuidType(Optional<Integer> version) {

    /** {@code uuid => !uuid_type {}} -- the unconstrained UUID, §5.5's {@code !uuid}. */
    public static final UuidType UNCONSTRAINED = new UuidType(Optional.empty());
}
