package io.ltr8.tson.schema.temporal;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.atom.AtomCoherence;
import io.ltr8.tson.schema.atom.AtomNarrowing;
import io.ltr8.tson.schema.meta.Atom;
import io.ltr8.tson.schema.meta.TypeChain;
import io.ltr8.tson.schema.numeric.IntegerSize;

import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The kernel's {@code time_type} constructor (§5.4's {@code time} atom, RFC 3339 {@code
 * full-time}). Pure constraint values, no parsing/validation behavior -- {@code tson-atom}'s
 * {@code TimeParser} holds one of these and does the actual reading/writing.
 *
 * <p>{@code fraction_digits} is a constraint on the value, not on its spelling (§5.5): {@code fraction_digits: N} admits a
 * value that is a whole number of 10<sup>-N</sup> seconds, so {@code 12:00:00.500} passes at {@code fraction_digits: 1}
 * and {@code fraction_digits: 0} admits whole seconds only. It is an ordered bound like {@code min}/{@code max} and
 * refines the same way. The family carries no timezone facet -- RFC 3339 {@code full-time}, which {@code spec}
 * pins, already makes the offset mandatory.
 *
 * <p>Also an {@link Atom} variant: {@code time => !temporal.time_type {}} is a constructor-application
 * instance (§5.5) whose resolved body is exactly {@link #UNCONSTRAINED}.
 */
@Typename(name = "temporal.time_type")
public record TimeType(TypeChain chain,
        Optional<OffsetTime> min, @Field("exclusive_min") Optional<OffsetTime> exclusiveMin,
                     Optional<OffsetTime> max, @Field("exclusive_max") Optional<OffsetTime> exclusiveMax,
                     @Field("fraction_digits") Optional<Long> fractionDigits) implements Atom {

    /**
     * Carries {@code @Record} because a second constructor exists below, and {@code ltr8-bind}'s own
     * constructor selection fails outright without it (see {@link IntegerSize}).
     */
    @Record
    public TimeType {
        chain = TypeChain.orEmpty(chain);
        if (min.isPresent() && exclusiveMin.isPresent()) {
            throw new IllegalArgumentException("min and exclusiveMin are mutually exclusive");
        }
        if (max.isPresent() && exclusiveMax.isPresent()) {
            throw new IllegalArgumentException("max and exclusiveMax are mutually exclusive");
        }
    }

    /** This body with an empty IS-A chain -- a value no resolver has placed in one. */
    public TimeType(Optional<OffsetTime> min, Optional<OffsetTime> exclusiveMin, Optional<OffsetTime> max,
            Optional<OffsetTime> exclusiveMax, Optional<Long> fractionDigits) {
        this(TypeChain.EMPTY, min, exclusiveMin, max, exclusiveMax, fractionDigits);
    }

    @Override
    public TimeType withChain(TypeChain chain) {
        return new TimeType(chain, min, exclusiveMin, max, exclusiveMax, fractionDigits);
    }

    /** The inclusive bounds alone, for the many callers that set neither an exclusive bound nor {@code fraction_digits}. */
    public TimeType(Optional<OffsetTime> min, Optional<OffsetTime> max) {
        this(min, Optional.empty(), max, Optional.empty(), Optional.empty());
    }

    /** The inclusive bounds and {@code fraction_digits}. */
    public TimeType(Optional<OffsetTime> min, Optional<OffsetTime> max, Optional<Long> fractionDigits) {
        this(min, Optional.empty(), max, Optional.empty(), fractionDigits);
    }

    /** {@code time => !temporal.time_type {}} -- the unconstrained time, §5.4's {@code !time}. */
    public static final TimeType UNCONSTRAINED = new TimeType(Optional.empty(), Optional.empty(), Optional.empty());

    /**
     * The value space's order, a time being its time of day in UTC (§5.5): {@code 11:00:00+01:00} ties with
     * {@code 10:00:00Z}, and {@code 23:30:00-02:00} sorts as {@code 01:30:00Z}. Never {@code compareTo}, whose
     * tie-break on the local time makes the offset part of the value, nor {@code isBefore}, which compares on one
     * shared day and so does not wrap at midnight.
     */
    public static final Comparator<OffsetTime> INSTANT_ORDER =
            Comparator.comparing(time -> time.withOffsetSameInstant(ZoneOffset.UTC).toLocalTime());

    /**
     * {@inheritDoc}
     *
     * <p>{@code fraction_digits} is an upper bound and may only fall (§5.5).
     */
    @Override
    public List<String> constraintsCheck(Atom refined) {
        if (!(refined instanceof TimeType other)) {
            return List.of("refines a time with " + refined.getClass().getSimpleName());
        }
        List<String> violations = new ArrayList<>();
        AtomNarrowing.checkLower(violations, AtomNarrowing.bound(min, exclusiveMin, "min", "exclusive_min"),
                AtomNarrowing.bound(other.min, other.exclusiveMin, "min", "exclusive_min"), INSTANT_ORDER);
        AtomNarrowing.checkUpper(violations, AtomNarrowing.bound(max, exclusiveMax, "max", "exclusive_max"),
                AtomNarrowing.bound(other.max, other.exclusiveMax, "max", "exclusive_max"), INSTANT_ORDER);
        AtomNarrowing.checkAtMost(violations, "fraction_digits", fractionDigits, other.fractionDigits);
        return List.copyOf(violations);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Each side is a field group ([TSON-SCHEMA] §5.11), so a range is empty either by a ceiling below
     * its floor or by the two meeting at one value an exclusive end removes.
     */
    @Override
    public List<String> coherenceCheck() {
        List<String> violations = new ArrayList<>();
        AtomCoherence.checkRange(violations, AtomNarrowing.bound(min, exclusiveMin, "min", "exclusive_min"),
                AtomNarrowing.bound(max, exclusiveMax, "max", "exclusive_max"), INSTANT_ORDER);
        FractionDigits.checkCoherent(violations, fractionDigits, "time");
        return List.copyOf(violations);
    }
}
