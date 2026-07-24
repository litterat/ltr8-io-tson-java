package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Typename;

import java.util.Optional;

/**
 * The meta-kernel's {@code duration_type} constructor (§5.4's {@code duration} atom, ISO 8601's
 * {@code PnYnMnDTnHnMnS}). Pure constraint values, no parsing/validation behavior -- {@code
 * tson-parser}'s {@code DurationParser} holds one of these and does the actual reading/writing.
 *
 * <p>Also an {@link Atom} variant (joined 2026-07-24): {@code duration => !duration_type {}} is a
 * constructor-application instance (§5.5) whose resolved body is exactly {@link #UNCONSTRAINED}.
 */
@Typename(name = "duration_type")
public record DurationType(Optional<IsoDuration> min, Optional<IsoDuration> max) implements Atom {

    /** {@code duration => !duration_type {}} -- the unconstrained duration, §5.4's {@code !duration}. */
    public static final DurationType UNCONSTRAINED = new DurationType(Optional.empty(), Optional.empty());
}
