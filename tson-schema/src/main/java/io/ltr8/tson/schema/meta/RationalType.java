package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Typename;

import java.util.Optional;

/**
 * The meta-kernel's {@code rational_type} constructor (§5.6's {@code rational} atom). Pure
 * constraint values, no parsing/validation behavior -- {@code tson-parser}'s {@code RationalParser}
 * holds one of these and does the actual reading/writing.
 *
 * <p>Also an {@link Atom} variant (joined 2026-07-24): {@code rational => !rational_type {}} is a
 * constructor-application instance (§5.5) whose resolved body is exactly {@link #UNCONSTRAINED}.
 */
@Typename(name = "rational_type")
public record RationalType(
        Optional<Rational> min,
        @Field("exclusive_min") Optional<Rational> exclusiveMin,
        Optional<Rational> max,
        @Field("exclusive_max") Optional<Rational> exclusiveMax,
        @Field("multiple_of") Optional<Rational> multipleOf) implements Atom {

    /** {@code rational => !rational_type {}} -- the unconstrained rational, §5.6's {@code !rational}. */
    public static final RationalType UNCONSTRAINED = new RationalType(
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    public RationalType {
        if (min.isPresent() && exclusiveMin.isPresent()) {
            throw new IllegalArgumentException("min and exclusiveMin are mutually exclusive");
        }
        if (max.isPresent() && exclusiveMax.isPresent()) {
            throw new IllegalArgumentException("max and exclusiveMax are mutually exclusive");
        }
    }
}
