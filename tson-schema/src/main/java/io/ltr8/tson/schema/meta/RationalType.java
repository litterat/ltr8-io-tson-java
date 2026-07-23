package io.ltr8.tson.schema.meta;

import java.util.Optional;

/**
 * The meta-kernel's {@code rational_type} constructor (§5.6's {@code rational} atom). Pure
 * constraint values, no parsing/validation behavior -- {@code tson-parser}'s {@code RationalParser}
 * holds one of these and does the actual reading/writing.
 */
public record RationalType(
        Optional<Rational> min,
        Optional<Rational> exclusiveMin,
        Optional<Rational> max,
        Optional<Rational> exclusiveMax,
        Optional<Rational> multipleOf) {

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
