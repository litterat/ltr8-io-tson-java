package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Typename;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The meta-kernel's {@code rational_type} constructor (§5.6's {@code rational} atom). Pure
 * constraint values, no parsing/validation behavior -- {@code tson-compiler}'s {@code RationalParser}
 * holds one of these and does the actual reading/writing.
 *
 * <p>Also an {@link Atom} variant: {@code rational => !rational_type {}} is a
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

    /**
     * {@inheritDoc}
     *
     * <p>Bounds are exact ({@link Rational} orders by cross-multiplication, so {@code 2/4} and
     * {@code 1/2} compare equal and either may restate the other). A refined {@code multiple_of}
     * narrows when it is an integer multiple of the source's: {@code a/b} steps by {@code c/d}
     * exactly when {@code a*d} divides evenly by {@code b*c}.
     */
    @Override
    public List<String> constraintsCheck(Atom refined) {
        if (!(refined instanceof RationalType other)) {
            return List.of("refines a rational with " + refined.getClass().getSimpleName());
        }
        List<String> violations = new ArrayList<>();
        AtomNarrowing.checkLower(violations, AtomNarrowing.bound(min, exclusiveMin, "min", "exclusive_min"),
                AtomNarrowing.bound(other.min, other.exclusiveMin, "min", "exclusive_min"));
        AtomNarrowing.checkUpper(violations, AtomNarrowing.bound(max, exclusiveMax, "max", "exclusive_max"),
                AtomNarrowing.bound(other.max, other.exclusiveMax, "max", "exclusive_max"));
        if (multipleOf.isPresent() && other.multipleOf.isPresent()
                && !isIntegerMultiple(other.multipleOf.get(), multipleOf.get())) {
            violations.add("multiple_of " + other.multipleOf.get() + " is not itself a multiple of the source's own "
                    + multipleOf.get());
        }
        return List.copyOf(violations);
    }

    /** Whether {@code step} divides evenly by {@code of} -- a zero step admits nothing to check against. */
    private static boolean isIntegerMultiple(Rational step, Rational of) {
        if (of.numerator().signum() == 0) {
            return true;
        }
        BigInteger dividend = step.numerator().multiply(of.denominator());
        BigInteger divisor = step.denominator().multiply(of.numerator());
        return dividend.remainder(divisor).signum() == 0;
    }
}
