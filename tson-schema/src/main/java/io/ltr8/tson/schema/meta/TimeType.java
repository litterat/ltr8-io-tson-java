package io.ltr8.tson.schema.meta;

import java.time.OffsetTime;
import java.util.Optional;

/**
 * The meta-kernel's {@code time_type} constructor (§5.4's {@code time} atom, RFC 3339 {@code
 * full-time}). Pure constraint values, no parsing/validation behavior -- {@code tson-parser}'s
 * {@code TimeParser} holds one of these and does the actual reading/writing.
 */
public record TimeType(Optional<OffsetTime> min, Optional<OffsetTime> max) {

    /** {@code time => !time_type {}} -- the unconstrained time, §5.4's {@code !time}. */
    public static final TimeType UNCONSTRAINED = new TimeType(Optional.empty(), Optional.empty());
}
