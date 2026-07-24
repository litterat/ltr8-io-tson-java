package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Typename;

import java.util.Optional;

/**
 * The meta-kernel's {@code duration_type} constructor (§5.4's {@code duration} atom, ISO 8601's
 * {@code PnYnMnDTnHnMnS}). Pure constraint values, no parsing/validation behavior -- {@code
 * tson-parser}'s {@code DurationParser} holds one of these and does the actual reading/writing.
 *
 * <p>{@code min}/{@code max} are the raw ISO 8601 duration text, {@code String}, not {@link
 * IsoDuration} -- matching the {@code TextType.pattern}/{@code UriType.pattern} precedent: {@code
 * IsoDuration} is this library's own record, so (like {@code Rational}/{@code Complex}) it collides
 * with generic binding's record auto-detection and would need an explicit {@code DataBridge} to
 * bind at all; a plain {@code String} field binds generically with no bridge, the same as every
 * other atom-constraint text field in this package. {@code DurationParser} parses these into {@link
 * IsoDuration} itself wherever it needs the parsed form (e.g. bound comparison), rather than this
 * record ever holding a parsed value.
 *
 * <p>Also an {@link Atom} variant (joined 2026-07-24): {@code duration => !duration_type {}} is a
 * constructor-application instance (§5.5) whose resolved body is exactly {@link #UNCONSTRAINED}.
 */
@Typename(name = "duration_type")
public record DurationType(Optional<String> min, Optional<String> max) implements Atom {

    /** {@code duration => !duration_type {}} -- the unconstrained duration, §5.4's {@code !duration}. */
    public static final DurationType UNCONSTRAINED = new DurationType(Optional.empty(), Optional.empty());
}
