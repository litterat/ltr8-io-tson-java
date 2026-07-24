package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Typename;

import java.time.OffsetTime;
import java.util.Optional;

/**
 * The meta-kernel's {@code time_type} constructor (§5.4's {@code time} atom, RFC 3339 {@code
 * full-time}). Pure constraint values, no parsing/validation behavior -- {@code tson-parser}'s
 * {@code TimeParser} holds one of these and does the actual reading/writing.
 *
 * <p>Also an {@link Atom} variant (joined 2026-07-24): {@code time => !time_type {}} is a
 * constructor-application instance (§5.5) whose resolved body is exactly {@link #UNCONSTRAINED}.
 */
@Typename(name = "time_type")
public record TimeType(Optional<OffsetTime> min, Optional<OffsetTime> max) implements Atom {

    /** {@code time => !time_type {}} -- the unconstrained time, §5.4's {@code !time}. */
    public static final TimeType UNCONSTRAINED = new TimeType(Optional.empty(), Optional.empty());
}
