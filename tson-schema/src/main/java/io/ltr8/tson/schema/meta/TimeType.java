package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;

import java.math.BigInteger;

import java.time.OffsetTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The meta-kernel's {@code time_type} constructor (§5.4's {@code time} atom, RFC 3339 {@code
 * full-time}). Pure constraint values, no parsing/validation behavior -- {@code tson-compiler}'s
 * {@code TimeParser} holds one of these and does the actual reading/writing.
 *
 * <p>{@code precision} bounds the fractional-second digits from above (§5.5): a token's fractional part
 * may carry at most that many, judged on the token as written, and {@code precision: 0} admits none. It
 * is an ordered bound like {@code min}/{@code max} and refines the same way. The family carries no
 * timezone facet -- RFC 3339 {@code full-time}, which {@code spec} pins, already makes the offset
 * mandatory.
 *
 * <p>Also an {@link Atom} variant: {@code time => !time_type {}} is a constructor-application
 * instance (§5.5) whose resolved body is exactly {@link #UNCONSTRAINED}.
 */
@Typename(name = "time_type")
public record TimeType(Optional<OffsetTime> min, Optional<OffsetTime> max, Optional<BigInteger> precision)
        implements Atom {

    /**
     * Carries {@code @Record} because a second constructor exists below, and {@code tson-bind}'s own
     * constructor selection fails outright without it (see {@link IntegerSize}).
     */
    @Record
    public TimeType {
    }

    /** The bounds alone, for the many callers that set no precision. */
    public TimeType(Optional<OffsetTime> min, Optional<OffsetTime> max) {
        this(min, max, Optional.empty());
    }

    /** {@code time => !time_type {}} -- the unconstrained time, §5.4's {@code !time}. */
    public static final TimeType UNCONSTRAINED = new TimeType(Optional.empty(), Optional.empty(), Optional.empty());

    /**
     * {@inheritDoc}
     *
     * <p>Bounds compare on {@link OffsetTime}'s own ordering, which normalises to the instant on a
     * shared day before comparing local time -- so a bound written in one offset is comparable with
     * one written in another rather than being rejected as incomparable. {@code precision} is an
     * upper bound and may only fall (§5.5).
     */
    @Override
    public List<String> constraintsCheck(Atom refined) {
        if (!(refined instanceof TimeType other)) {
            return List.of("refines a time with " + refined.getClass().getSimpleName());
        }
        List<String> violations = new ArrayList<>();
        AtomNarrowing.checkAtLeast(violations, "min", min, other.min);
        AtomNarrowing.checkAtMost(violations, "max", max, other.max);
        AtomNarrowing.checkAtMost(violations, "precision", precision, other.precision);
        return List.copyOf(violations);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Both bounds are inclusive -- this family has no exclusive spelling -- so the only empty
     * range is a ceiling below its own floor. Compared on {@link java.time.OffsetTime}'s own
     * ordering, which normalises to the instant on a shared day first, so a pair written in two
     * different offsets is still judged rather than waved through as incomparable.
     */
    @Override
    public List<String> coherenceCheck() {
        List<String> violations = new ArrayList<>();
        AtomCoherence.checkOrdered(violations, "min", min, "max", max);
        return List.copyOf(violations);
    }
}
