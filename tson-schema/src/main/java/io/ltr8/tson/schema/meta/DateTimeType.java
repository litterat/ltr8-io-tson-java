package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Typename;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * The meta-kernel's {@code datetime_type} constructor (§5.4's {@code datetime} atom, RFC 3339
 * {@code date-time}). Pure constraint values, no parsing/validation behavior -- {@code
 * tson-parser}'s {@code DateTimeParser} holds one of these and does the actual reading/writing.
 *
 * <p>Also an {@link Atom} variant (joined 2026-07-24): {@code datetime => !datetime_type {}} is a
 * constructor-application instance (§5.5) whose resolved body is exactly {@link #UNCONSTRAINED}.
 */
@Typename(name = "datetime_type")
public record DateTimeType(Optional<OffsetDateTime> min, Optional<OffsetDateTime> max) implements Atom {

    /** {@code datetime => !datetime_type {}} -- the unconstrained datetime, §5.4's {@code !datetime}. */
    public static final DateTimeType UNCONSTRAINED = new DateTimeType(Optional.empty(), Optional.empty());
}
