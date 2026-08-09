package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Typename;

import java.util.Optional;

/**
 * The meta-kernel's {@code uuid_type} constructor (§5.5's {@code uuid} atom, RFC 9562). Pure
 * constraint values, no parsing/validation behavior -- {@code tson-compiler}'s {@code UuidParser}
 * holds one of these and does the actual reading/writing.
 *
 * <p>Also an {@link Atom} variant: {@code uuid => !uuid_type {}} is a constructor-application
 * instance (§5.5) whose resolved body is exactly {@link #UNCONSTRAINED}.
 */
@Typename(name = "uuid_type")
public record UuidType(Optional<Integer> version) implements Atom {

    /** {@code uuid => !uuid_type {}} -- the unconstrained UUID, §5.5's {@code !uuid}. */
    public static final UuidType UNCONSTRAINED = new UuidType(Optional.empty());

    /**
     * {@inheritDoc}
     *
     * <p><b>No narrowing check.</b> {@link #version} selects a generation scheme rather than
     * measuring anything, so it does not order -- version 7 is not narrower than version 4, it is a
     * different set of values. Selector facets are left unchecked across this package rather than
     * treated as identity facets a refinement may only restate; {@link ComplexType} carries the
     * reasoning and the spec citation.
     */
}
