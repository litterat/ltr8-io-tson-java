package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The meta-kernel's {@code date_type} constructor (§5.4's {@code date} atom, RFC 3339 {@code
 * full-date}). Pure constraint values, no parsing/validation behavior -- {@code tson-compiler}'s
 * {@code DateParser} holds one of these and does the actual reading/writing.
 *
 * <p>Also an {@link Atom} variant: {@code date => !date_type {}} is a constructor-application
 * instance (§5.5) whose resolved body is exactly {@link #UNCONSTRAINED}.
 */
@Typename(name = "date_type")
public record DateType(Optional<LocalDate> min, @Field("exclusive_min") Optional<LocalDate> exclusiveMin,
                       Optional<LocalDate> max, @Field("exclusive_max") Optional<LocalDate> exclusiveMax)
        implements Atom {

    /**
     * Carries {@code @Record} because the inclusive-bounds convenience constructor below is a second
     * public one, and {@code tson-bind}'s constructor selection fails outright without it (see {@link
     * IntegerSize}). Mutual exclusion within each side is this constructor's, as for {@link IntegerType}:
     * the field group ([TSON-SCHEMA] §5.11) makes it unrepresentable in the schema and this makes it
     * unconstructable here.
     */
    @Record
    public DateType {
        if (min.isPresent() && exclusiveMin.isPresent()) {
            throw new IllegalArgumentException("min and exclusiveMin are mutually exclusive");
        }
        if (max.isPresent() && exclusiveMax.isPresent()) {
            throw new IllegalArgumentException("max and exclusiveMax are mutually exclusive");
        }
    }

    /** The inclusive bounds alone, for the callers that state no exclusive one. */
    public DateType(Optional<LocalDate> min, Optional<LocalDate> max) {
        this(min, Optional.empty(), max, Optional.empty());
    }

    /** {@code date => !date_type {}} -- the unconstrained date, §5.4's {@code !date}. */
    public static final DateType UNCONSTRAINED = new DateType(Optional.empty(), Optional.empty());

    /**
     * {@inheritDoc}
     *
     * <p>Each side is a field group ([TSON-SCHEMA] §5.11), so at most one bound per side is present and
     * the pair is compared as one bound, exclusive or not -- the same fold the numeric families use, on
     * calendar order.
     */
    @Override
    public List<String> constraintsCheck(Atom refined) {
        if (!(refined instanceof DateType other)) {
            return List.of("refines a date with " + refined.getClass().getSimpleName());
        }
        List<String> violations = new ArrayList<>();
        AtomNarrowing.checkLower(violations, AtomNarrowing.bound(min, exclusiveMin, "min", "exclusive_min"),
                AtomNarrowing.bound(other.min, other.exclusiveMin, "min", "exclusive_min"));
        AtomNarrowing.checkUpper(violations, AtomNarrowing.bound(max, exclusiveMax, "max", "exclusive_max"),
                AtomNarrowing.bound(other.max, other.exclusiveMax, "max", "exclusive_max"));
        return List.copyOf(violations);
    }

    /**
     * {@inheritDoc}
     *
     * <p>A date's value space is discrete and totally ordered, so the range check is the numeric
     * families' exactly: a ceiling below its floor is empty, and so is a pair meeting at one date that
     * an exclusive end removes.
     */
    @Override
    public List<String> coherenceCheck() {
        List<String> violations = new ArrayList<>();
        AtomCoherence.checkRange(violations, AtomNarrowing.bound(min, exclusiveMin, "min", "exclusive_min"),
                AtomNarrowing.bound(max, exclusiveMax, "max", "exclusive_max"));
        return List.copyOf(violations);
    }
}
