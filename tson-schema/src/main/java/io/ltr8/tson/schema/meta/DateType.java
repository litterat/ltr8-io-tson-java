package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Typename;

import java.time.LocalDate;
import java.util.Optional;

/**
 * The meta-kernel's {@code date_type} constructor (§5.4's {@code date} atom, RFC 3339 {@code
 * full-date}). Pure constraint values, no parsing/validation behavior -- {@code tson-parser}'s
 * {@code DateParser} holds one of these and does the actual reading/writing.
 *
 * <p>Also an {@link Atom} variant (joined 2026-07-24): {@code date => !date_type {}} is a
 * constructor-application instance (§5.5) whose resolved body is exactly {@link #UNCONSTRAINED}.
 */
@Typename(name = "date_type")
public record DateType(Optional<LocalDate> min, Optional<LocalDate> max) implements Atom {

    /** {@code date => !date_type {}} -- the unconstrained date, §5.4's {@code !date}. */
    public static final DateType UNCONSTRAINED = new DateType(Optional.empty(), Optional.empty());
}
