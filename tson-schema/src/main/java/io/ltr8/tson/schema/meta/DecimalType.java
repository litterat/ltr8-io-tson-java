package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Typename;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The meta-kernel's {@code decimal_type} constructor (§5.6's {@code number} atom -- SQL's exact
 * tier, ISO/IEC 11404 {@code scaled}). Pure constraint values, no parsing/validation behavior --
 * {@code tson-compiler}'s {@code DecimalParser} holds one of these and does the actual
 * reading/writing.
 *
 * <p>Also an {@link Atom} variant: {@code number => !decimal_type {}} is a constructor-application
 * instance (§5.5) whose resolved body is exactly {@link #UNCONSTRAINED}.
 */
@Typename(name = "decimal_type")
public record DecimalType(
        Optional<BigDecimal> min,
        @Field("exclusive_min") Optional<BigDecimal> exclusiveMin,
        Optional<BigDecimal> max,
        @Field("exclusive_max") Optional<BigDecimal> exclusiveMax,
        @Field("multiple_of") Optional<BigDecimal> multipleOf,
        @Field("total_digits") Optional<Integer> totalDigits,
        @Field("fraction_digits") Optional<Integer> fractionDigits) implements Atom {

    /** {@code number => !decimal_type {}} -- the unconstrained exact number, §5.6's {@code !number}. */
    public static final DecimalType UNCONSTRAINED = new DecimalType(
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty());

    public DecimalType {
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
     * <p>Both precision facets are ceilings: {@code total_digits} and {@code fraction_digits} cap
     * how much a value may carry, so a refinement may only lower them. {@code multiple_of} narrows
     * when the refined step is itself a multiple of the source's -- a 0.05 step may be tightened to
     * 0.10 (every dime is a nickel) but not to 0.02.
     *
     * <p>Comparison is by numeric value, not by scale: {@code min: 1.50} restates {@code min: 1.5}
     * rather than changing it, which is {@link java.math.BigDecimal#compareTo}'s own contract and
     * the reason equality is never used here.
     */
    @Override
    public List<String> constraintsCheck(Atom refined) {
        if (!(refined instanceof DecimalType other)) {
            return List.of("refines a decimal with " + refined.getClass().getSimpleName());
        }
        List<String> violations = new ArrayList<>();
        AtomNarrowing.checkLower(violations, AtomNarrowing.bound(min, exclusiveMin, "min", "exclusive_min"),
                AtomNarrowing.bound(other.min, other.exclusiveMin, "min", "exclusive_min"));
        AtomNarrowing.checkUpper(violations, AtomNarrowing.bound(max, exclusiveMax, "max", "exclusive_max"),
                AtomNarrowing.bound(other.max, other.exclusiveMax, "max", "exclusive_max"));
        AtomNarrowing.checkAtMost(violations, "total_digits", totalDigits, other.totalDigits);
        AtomNarrowing.checkAtMost(violations, "fraction_digits", fractionDigits, other.fractionDigits);
        if (multipleOf.isPresent() && other.multipleOf.isPresent() && multipleOf.get().signum() != 0
                && other.multipleOf.get().remainder(multipleOf.get()).compareTo(BigDecimal.ZERO) != 0) {
            violations.add("multiple_of " + other.multipleOf.get() + " is not itself a multiple of the source's own "
                    + multipleOf.get());
        }
        return List.copyOf(violations);
    }
}
