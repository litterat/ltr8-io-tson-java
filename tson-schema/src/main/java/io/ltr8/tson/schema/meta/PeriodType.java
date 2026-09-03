package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;

import java.math.BigInteger;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * meta's {@code period_type} constructor: a calendar span, as a signed integer number of months
 * ([TSON-SCHEMA] §5.5). Instance is {@code period} in core.
 *
 * <p>The calendar half of what one duration used to carry, and the reason {@link DurationType} can be
 * totally ordered: a month has no fixed length, so months and seconds are two value spaces rather than one
 * partially ordered one. A span that is genuinely both is a record with a field of each.
 *
 * <p>The lexical form admits a Y component, an M component, or both in that order -- no fraction, no W or D
 * component and no {@code T} part. {@code P1Y} and {@code P12M} are one value, so bounds compare the value
 * and not the token. {@code multiple_of} is a strictly positive period and ignores sign when testing.
 *
 * <p>The host type is {@link Period}, whose {@code days} is always zero here. It is not {@link Comparable} --
 * for the general shape it cannot be, months and days having no common length -- so every comparison here
 * goes through {@link #months}, which is the value this family is defined on.
 */
@Typename(name = "period_type")
public record PeriodType(
        Optional<Period> min,
        @Field("exclusive_min") Optional<Period> exclusiveMin,
        Optional<Period> max,
        @Field("exclusive_max") Optional<Period> exclusiveMax,
        @Field("multiple_of") Optional<Period> multipleOf) implements Atom {

    /** {@code period => !period_type {}} -- the unconstrained period. */
    public static final PeriodType UNCONSTRAINED = new PeriodType(Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty());

    @Record
    public PeriodType {
        if (min.isPresent() && exclusiveMin.isPresent()) {
            throw new IllegalArgumentException("min and exclusiveMin are mutually exclusive");
        }
        if (max.isPresent() && exclusiveMax.isPresent()) {
            throw new IllegalArgumentException("max and exclusiveMax are mutually exclusive");
        }
    }

    /** The inclusive bounds alone. */
    public PeriodType(Optional<Period> min, Optional<Period> max) {
        this(min, Optional.empty(), max, Optional.empty(), Optional.empty());
    }

    /** The value a period denotes: its total months, years counted as twelve. */
    public static BigInteger months(Period period) {
        return BigInteger.valueOf(period.toTotalMonths());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Each side is a field group ([TSON-SCHEMA] §5.11), compared on {@link #months} -- the value, not the
     * token, so {@code P1Y} and {@code P12M} are one bound. {@code multiple_of} narrows when the refined step
     * is an integer multiple of the source's, sign ignored.
     */
    @Override
    public List<String> constraintsCheck(Atom refined) {
        if (!(refined instanceof PeriodType other)) {
            return List.of("refines a period with " + refined.getClass().getSimpleName());
        }
        List<String> violations = new ArrayList<>();
        AtomNarrowing.checkLower(violations, bound(min, exclusiveMin, "min", "exclusive_min"),
                bound(other.min, other.exclusiveMin, "min", "exclusive_min"));
        AtomNarrowing.checkUpper(violations, bound(max, exclusiveMax, "max", "exclusive_max"),
                bound(other.max, other.exclusiveMax, "max", "exclusive_max"));
        if (multipleOf.isPresent() && other.multipleOf.isPresent()
                && !isMultiple(other.multipleOf.get(), multipleOf.get())) {
            violations.add("multiple_of " + other.multipleOf.get() + " is not itself a multiple of the source's own "
                    + multipleOf.get());
        }
        return List.copyOf(violations);
    }

    /** {@inheritDoc} <p>As {@link DurationType#coherenceCheck}, on the months a period denotes. */
    @Override
    public List<String> coherenceCheck() {
        List<String> violations = new ArrayList<>();
        AtomCoherence.checkRange(violations, bound(min, exclusiveMin, "min", "exclusive_min"),
                bound(max, exclusiveMax, "max", "exclusive_max"));
        multipleOf.filter(step -> months(step).signum() <= 0).ifPresent(step ->
                violations.add("multiple_of " + step + " is not strictly positive"));
        return List.copyOf(violations);
    }

    /** Whether {@code value} is an integer multiple of {@code step}, sign ignored on both. */
    public static boolean isMultiple(Period value, Period step) {
        BigInteger magnitude = months(step).abs();
        return magnitude.signum() != 0 && months(value).abs().mod(magnitude).signum() == 0;
    }

    /** A bound over {@link #months}, the value space this family's ordering is defined on. */
    private static AtomNarrowing.Bound<BigInteger> bound(Optional<Period> inclusive, Optional<Period> exclusive,
            String inclusiveFacet, String exclusiveFacet) {
        return AtomNarrowing.bound(inclusive.map(PeriodType::months), exclusive.map(PeriodType::months),
                inclusiveFacet, exclusiveFacet);
    }
}
