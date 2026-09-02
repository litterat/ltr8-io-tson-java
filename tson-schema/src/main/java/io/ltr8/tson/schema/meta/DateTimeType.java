package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;

import java.math.BigInteger;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The meta-kernel's {@code datetime_type} constructor (§5.4's {@code datetime} atom, RFC 3339
 * {@code date-time}). Pure constraint values, no parsing/validation behavior -- {@code
 * tson-compiler}'s {@code DateTimeParser} holds one of these and does the actual reading/writing.
 *
 * <p>{@code precision} bounds the fractional-second digits from above (§5.5), exactly as it does on
 * {@link TimeType}: at most that many digits on the token as written, and {@code precision: 0} admits
 * none. The family carries no timezone facet -- RFC 3339 {@code date-time} already makes the offset
 * mandatory.
 *
 * <p>Also an {@link Atom} variant: {@code datetime => !datetime_type {}} is a
 * constructor-application instance (§5.5) whose resolved body is exactly {@link #UNCONSTRAINED}.
 */
@Typename(name = "datetime_type")
public record DateTimeType(Optional<OffsetDateTime> min, @Field("exclusive_min") Optional<OffsetDateTime> exclusiveMin,
                     Optional<OffsetDateTime> max, @Field("exclusive_max") Optional<OffsetDateTime> exclusiveMax,
                     Optional<BigInteger> precision) implements Atom {

    /**
     * Carries {@code @Record} because a second constructor exists below, and {@code tson-bind}'s own
     * constructor selection fails outright without it (see {@link IntegerSize}).
     */
    @Record
    public DateTimeType {
        if (min.isPresent() && exclusiveMin.isPresent()) {
            throw new IllegalArgumentException("min and exclusiveMin are mutually exclusive");
        }
        if (max.isPresent() && exclusiveMax.isPresent()) {
            throw new IllegalArgumentException("max and exclusiveMax are mutually exclusive");
        }
    }

    /** The inclusive bounds alone, for the many callers that set neither an exclusive bound nor a precision. */
    public DateTimeType(Optional<OffsetDateTime> min, Optional<OffsetDateTime> max) {
        this(min, Optional.empty(), max, Optional.empty(), Optional.empty());
    }

    /** The inclusive bounds and a precision. */
    public DateTimeType(Optional<OffsetDateTime> min, Optional<OffsetDateTime> max, Optional<BigInteger> precision) {
        this(min, Optional.empty(), max, Optional.empty(), precision);
    }

    /** {@code datetime => !datetime_type {}} -- the unconstrained datetime, §5.4's {@code !datetime}. */
    public static final DateTimeType UNCONSTRAINED =
            new DateTimeType(Optional.empty(), Optional.empty(), Optional.empty());

    /**
     * {@inheritDoc}
     *
     * <p>Bounds compare on {@link OffsetDateTime}'s own ordering, which compares the instant first,
     * so two bounds written in different offsets are still comparable. {@code precision} is an upper
     * bound and may only fall (§5.5).
     */
    @Override
    public List<String> constraintsCheck(Atom refined) {
        if (!(refined instanceof DateTimeType other)) {
            return List.of("refines a datetime with " + refined.getClass().getSimpleName());
        }
        List<String> violations = new ArrayList<>();
        AtomNarrowing.checkLower(violations, AtomNarrowing.bound(min, exclusiveMin, "min", "exclusive_min"),
                AtomNarrowing.bound(other.min, other.exclusiveMin, "min", "exclusive_min"));
        AtomNarrowing.checkUpper(violations, AtomNarrowing.bound(max, exclusiveMax, "max", "exclusive_max"),
                AtomNarrowing.bound(other.max, other.exclusiveMax, "max", "exclusive_max"));
        AtomNarrowing.checkAtMost(violations, "precision", precision, other.precision);
        return List.copyOf(violations);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Each side is a field group ([TSON-SCHEMA] §5.11), so a range is empty either by a ceiling below
     * its floor or by the two meeting at one value an exclusive end removes. Compared on {@link java.time.OffsetDateTime}'s own
     * ordering, which compares the instant first, so a pair written in two different offsets is
     * still judged rather than waved through as incomparable.
     */
    @Override
    public List<String> coherenceCheck() {
        List<String> violations = new ArrayList<>();
        AtomCoherence.checkRange(violations, AtomNarrowing.bound(min, exclusiveMin, "min", "exclusive_min"),
                AtomNarrowing.bound(max, exclusiveMax, "max", "exclusive_max"));
        return List.copyOf(violations);
    }
}
