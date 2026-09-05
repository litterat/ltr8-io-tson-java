package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;

import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * meta's {@code duration_type} constructor: elapsed time, as a signed <b>exact decimal</b> number of seconds
 * ([TSON-SCHEMA] §5.5) -- {@code number}'s value space in seconds, bounded at both ends by a signed 64-bit
 * count of nanoseconds. Instance is {@code duration} in core.
 *
 * <p><b>Totally ordered, and that is what the facets rest on.</b> The lexical form admits no Y and no
 * month-M component -- {@code P1M} is a month and belongs to {@link PeriodType}, a minute being {@code PT1M}
 * -- so every value has a fixed length: a day is exactly 86400 s and a week 7 days. That is what lets this
 * family carry ordered bounds at all. While one type carried both halves the order was partial (a
 * calendar-based span has no fixed length to compare against a clock-based one) and the bounds it declared
 * could not be enforced.
 *
 * <p>Bounds compare the <em>value</em>, not the token, so {@code PT90M}, {@code PT1H30M} and {@code
 * P0DT5400S} are one value and compare equal. {@code precision} constrains the value exactly as {@link
 * TimeType}'s does -- a whole number of 10⁻ᴺ seconds, whatever the spelling. {@code multiple_of} is a
 * strictly positive duration and ignores sign when testing, so {@code -PT30M} is a multiple of {@code
 * PT15M}.
 *
 * <p>The host type is {@link Duration}: signed, {@link Comparable}, and a nanosecond count, which is exactly
 * the value space -- so a bound this record carries is one {@code Duration} holds without loss, and {@link
 * #isMultiple}'s own {@code toNanos} cannot overflow. A bound built programmatically rather than read is
 * checked against the ceiling by {@link #coherenceCheck}, which is what keeps that true.
 */
@Typename(name = "duration_type")
public record DurationType(
        Optional<Duration> min,
        @Field("exclusive_min") Optional<Duration> exclusiveMin,
        Optional<Duration> max,
        @Field("exclusive_max") Optional<Duration> exclusiveMax,
        Optional<BigInteger> precision,
        @Field("multiple_of") Optional<Duration> multipleOf) implements Atom {

    /** {@code duration => !duration_type {}} -- the unconstrained duration. */
    public static final DurationType UNCONSTRAINED = new DurationType(Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    @Record
    public DurationType {
        if (min.isPresent() && exclusiveMin.isPresent()) {
            throw new IllegalArgumentException("min and exclusiveMin are mutually exclusive");
        }
        if (max.isPresent() && exclusiveMax.isPresent()) {
            throw new IllegalArgumentException("max and exclusiveMax are mutually exclusive");
        }
    }

    /** The inclusive bounds alone, for the callers that state neither an exclusive bound nor a step. */
    public DurationType(Optional<Duration> min, Optional<Duration> max) {
        this(min, Optional.empty(), max, Optional.empty(), Optional.empty(), Optional.empty());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Each side is a field group ([TSON-SCHEMA] §5.11), compared as one bound on {@link Duration}'s own
     * ordering. {@code precision} is a ceiling and may only fall; {@code multiple_of} narrows when the
     * refined step is an integer multiple of the source's, sign ignored -- the numeric families' rule, on a
     * value space that is a signed number of seconds.
     */
    @Override
    public List<String> constraintsCheck(Atom refined) {
        if (!(refined instanceof DurationType other)) {
            return List.of("refines a duration with " + refined.getClass().getSimpleName());
        }
        List<String> violations = new ArrayList<>();
        AtomNarrowing.checkLower(violations, AtomNarrowing.bound(min, exclusiveMin, "min", "exclusive_min"),
                AtomNarrowing.bound(other.min, other.exclusiveMin, "min", "exclusive_min"));
        AtomNarrowing.checkUpper(violations, AtomNarrowing.bound(max, exclusiveMax, "max", "exclusive_max"),
                AtomNarrowing.bound(other.max, other.exclusiveMax, "max", "exclusive_max"));
        AtomNarrowing.checkAtMost(violations, "precision", precision, other.precision);
        if (multipleOf.isPresent() && other.multipleOf.isPresent()
                && !isMultiple(other.multipleOf.get(), multipleOf.get())) {
            violations.add("multiple_of " + other.multipleOf.get() + " is not itself a multiple of the source's own "
                    + multipleOf.get());
        }
        return List.copyOf(violations);
    }

    /**
     * {@inheritDoc}
     *
     * <p>A range is empty either by a ceiling below its floor or by the two meeting at one value an exclusive
     * end removes. {@code multiple_of} must be strictly positive: zero divides nothing and a negative step
     * says nothing a positive one does not, so both are schema-load errors rather than vacuous facets.
     *
     * <p><b>Every bound is checked against the value space's own ends.</b> A bound read from a schema is
     * already inside them, its token having gone through {@code DurationParser} -- but a {@link DurationType}
     * built in Java is not, and {@link Duration} would take a span three orders of magnitude past the
     * ceiling. A facet naming a value the type does not have admits nothing, which is this check's own
     * subject, and it is also what keeps {@link #isMultiple}'s {@code toNanos} from overflowing.
     * {@code precision} beyond nine is refused for the same reason from the other end: the value space has
     * no tenth digit for a schema to ask about.
     */
    @Override
    public List<String> coherenceCheck() {
        List<String> violations = new ArrayList<>();
        AtomCoherence.checkRange(violations, AtomNarrowing.bound(min, exclusiveMin, "min", "exclusive_min"),
                AtomNarrowing.bound(max, exclusiveMax, "max", "exclusive_max"));
        AtomCoherence.checkNonNegative(violations, "precision", precision.map(BigInteger::intValueExact));
        precision.filter(digits -> digits.compareTo(BigInteger.valueOf(MAX_FRACTION_DIGITS)) > 0)
                .ifPresent(digits -> violations.add("precision " + digits + " is finer than the "
                        + MAX_FRACTION_DIGITS + " fractional-second digits a duration carries"));
        multipleOf.filter(step -> step.isZero() || step.isNegative()).ifPresent(step ->
                violations.add("multiple_of " + step + " is not strictly positive"));
        checkInRange(violations, "min", min);
        checkInRange(violations, "exclusive_min", exclusiveMin);
        checkInRange(violations, "max", max);
        checkInRange(violations, "exclusive_max", exclusiveMax);
        checkInRange(violations, "multiple_of", multipleOf);
        return List.copyOf(violations);
    }

    /**
     * The value space's ends, as [TSON-SCHEMA] §5.5 fixes them: a signed 64-bit count of nanoseconds, stated
     * as a magnitude so that negating an admitted duration always yields an admitted one. {@code
     * DurationParser} holds the same two numbers for the read side and carries the reasoning.
     */
    private static final int MAX_FRACTION_DIGITS = 9;

    private static final Duration MAX_MAGNITUDE = Duration.ofSeconds(Long.MAX_VALUE / 1_000_000_000L,
            Long.MAX_VALUE % 1_000_000_000L);

    private static void checkInRange(List<String> out, String facet, Optional<Duration> bound) {
        bound.filter(value -> value.abs().compareTo(MAX_MAGNITUDE) > 0).ifPresent(value ->
                out.add(facet + " " + value + " is longer than the widest duration there is"));
    }

    /**
     * Whether {@code value} is an integer multiple of {@code step}, sign ignored on both.
     *
     * <p>{@code toNanos} is total here rather than merely usually safe: §5.5's ceiling is exactly the range
     * it has, so a duration that reaches this method is one it can count. {@link #coherenceCheck} refuses a
     * step outside that range, and {@code DurationParser} refuses a value outside it.
     */
    public static boolean isMultiple(Duration value, Duration step) {
        Duration magnitude = step.abs();
        return !magnitude.isZero() && value.abs().toNanos() % magnitude.toNanos() == 0;
    }
}
