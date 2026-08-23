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
 * <p><b>{@code precision} and {@code require_timezone} are carried but not enforced.</b> The constructor
 * declares them, so the body must too -- a component-less field is a value a schema states and this model
 * silently loses ({@code RecordBindReader} refuses such a binding outright). What is missing is downstream:
 * {@code DateTimeParser} rejects a schema that sets either, rather than accepting one and ignoring the facet.
 *
 * <p>Also an {@link Atom} variant: {@code datetime => !datetime_type {}} is a
 * constructor-application instance (§5.5) whose resolved body is exactly {@link #UNCONSTRAINED}.
 */
@Typename(name = "datetime_type")
public record DateTimeType(Optional<OffsetDateTime> min, Optional<OffsetDateTime> max, Optional<BigInteger> precision,
                          @Field("require_timezone") Optional<Boolean> requireTimezone) implements Atom {

    /**
     * Carries {@code @Record} because a second constructor exists below, and {@code tson-bind}'s own
     * constructor selection fails outright without it (see {@link IntegerSize}).
     */
    @Record
    public DateTimeType {
    }

    /** The bounds alone, for the many callers that set no facet -- the two unenforced ones default to absent. */
    public DateTimeType(Optional<OffsetDateTime> min, Optional<OffsetDateTime> max) {
        this(min, max, Optional.empty(), Optional.empty());
    }

    /** {@code datetime => !datetime_type {}} -- the unconstrained datetime, §5.4's {@code !datetime}. */
    public static final DateTimeType UNCONSTRAINED = new DateTimeType(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    /**
     * {@inheritDoc}
     *
     * <p>Bounds compare on {@link OffsetDateTime}'s own ordering, which compares the instant first,
     * so two bounds written in different offsets are still comparable.
     */
    @Override
    public List<String> constraintsCheck(Atom refined) {
        if (!(refined instanceof DateTimeType other)) {
            return List.of("refines a datetime with " + refined.getClass().getSimpleName());
        }
        List<String> violations = new ArrayList<>();
        AtomNarrowing.checkAtLeast(violations, "min", min, other.min);
        AtomNarrowing.checkAtMost(violations, "max", max, other.max);
        return List.copyOf(violations);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Both bounds are inclusive -- this family has no exclusive spelling -- so the only empty
     * range is a ceiling below its own floor. Compared on {@link java.time.OffsetDateTime}'s own
     * ordering, which compares the instant first, so a pair written in two different offsets is
     * still judged rather than waved through as incomparable.
     */
    @Override
    public List<String> coherenceCheck() {
        List<String> violations = new ArrayList<>();
        AtomCoherence.checkOrdered(violations, "min", min, "max", max);
        return List.copyOf(violations);
    }
}
