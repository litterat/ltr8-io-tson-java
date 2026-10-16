package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;

import java.util.List;
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
public record UuidType(TypeChain chain,
        Optional<Integer> version) implements Atom {
    /** The chain normalised: absent and empty are one list. */
    @Record
    public UuidType {
        chain = TypeChain.orEmpty(chain);
    }

    /** This body with an empty IS-A chain -- a value no resolver has placed in one. */
    public UuidType(Optional<Integer> version) {
        this(TypeChain.EMPTY, version);
    }

    @Override
    public UuidType withChain(TypeChain chain) {
        return new UuidType(chain, version);
    }

    /** {@code uuid => !uuid_type {}} -- the unconstrained UUID, §5.5's {@code !uuid}. */
    public static final UuidType UNCONSTRAINED = new UuidType(Optional.empty());


    /**
     * {@inheritDoc}
     *
     * <p>{@code version} names a UUID layout rather than a bound: the versions are disjoint sets, not
     * progressively smaller ones, so a refinement that changes one claims an IS-A that does not hold -- no v7
     * UUID is a v4 UUID. Setting it where the source left it unset does narrow, every versioned UUID being a
     * UUID, so that stays permitted and only a change is refused ({@link
     * AtomNarrowing#checkSettableOnce}).
     */
    @Override
    public java.util.List<String> constraintsCheck(Atom refined) {
        if (!(refined instanceof UuidType other)) {
            return java.util.List.of("refines a uuid with " + refined.getClass().getSimpleName());
        }
        java.util.List<String> violations = new java.util.ArrayList<>();
        AtomNarrowing.checkSettableOnce(violations, "version", version, other.version);
        return java.util.List.copyOf(violations);
    }
}
