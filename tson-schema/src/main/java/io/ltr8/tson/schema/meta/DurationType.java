package io.ltr8.tson.schema.meta;

import java.util.Optional;

/**
 * The meta-kernel's {@code duration_type} constructor (§5.4's {@code duration} atom, ISO 8601's
 * {@code PnYnMnDTnHnMnS}). Pure constraint values, no parsing/validation behavior -- {@code
 * tson-parser}'s {@code DurationParser} holds one of these and does the actual reading/writing.
 */
public record DurationType(Optional<IsoDuration> min, Optional<IsoDuration> max) {

    /** {@code duration => !duration_type {}} -- the unconstrained duration, §5.4's {@code !duration}. */
    public static final DurationType UNCONSTRAINED = new DurationType(Optional.empty(), Optional.empty());
}
