package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Typename;

import java.util.Optional;

/**
 * The meta-kernel's {@code uuid_type} constructor (§5.5's {@code uuid} atom, RFC 9562). Pure
 * constraint values, no parsing/validation behavior -- {@code tson-parser}'s {@code UuidParser}
 * holds one of these and does the actual reading/writing.
 *
 * <p>Also an {@link Atom} variant (joined 2026-07-24): {@code uuid => !uuid_type {}} is a
 * constructor-application instance (§5.5) whose resolved body is exactly {@link #UNCONSTRAINED}.
 */
@Typename(name = "uuid_type")
public record UuidType(Optional<Integer> version) implements Atom {

    /** {@code uuid => !uuid_type {}} -- the unconstrained UUID, §5.5's {@code !uuid}. */
    public static final UuidType UNCONSTRAINED = new UuidType(Optional.empty());
}
