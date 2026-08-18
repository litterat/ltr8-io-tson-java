package io.ltr8.tson.schema.meta;

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
public record DateType(Optional<LocalDate> min, Optional<LocalDate> max) implements Atom {

    /** {@code date => !date_type {}} -- the unconstrained date, §5.4's {@code !date}. */
    public static final DateType UNCONSTRAINED = new DateType(Optional.empty(), Optional.empty());

    /**
     * {@inheritDoc}
     *
     * <p>Both bounds are inclusive -- this family has no exclusive facet -- so a refinement may only
     * move them inward.
     */
    @Override
    public List<String> constraintsCheck(Atom refined) {
        if (!(refined instanceof DateType other)) {
            return List.of("refines a date with " + refined.getClass().getSimpleName());
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
     * range is a ceiling below its own floor, compared on calendar order.
     */
    @Override
    public List<String> coherenceCheck() {
        List<String> violations = new ArrayList<>();
        AtomCoherence.checkOrdered(violations, "min", min, "max", max);
        return List.copyOf(violations);
    }
}
