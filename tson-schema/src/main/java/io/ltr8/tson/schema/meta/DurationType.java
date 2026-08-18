package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Typename;

import java.util.Optional;

/**
 * The meta-kernel's {@code duration_type} constructor (§5.4's {@code duration} atom, ISO 8601's
 * {@code PnYnMnDTnHnMnS}). Pure constraint values, no parsing/validation behavior -- {@code
 * tson-compiler}'s {@code DurationParser} holds one of these and does the actual reading/writing.
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
 * <p>Also an {@link Atom} variant: {@code duration => !duration_type {}} is a
 * constructor-application instance (§5.5) whose resolved body is exactly {@link #UNCONSTRAINED}.
 *
 * <p><b>No narrowing check, and no coherence check.</b> Both {@link Atom#constraintsCheck} and
 * {@link Atom#coherenceCheck} are left at their permissive defaults here, for one reason: this
 * family's bounds are unparsed ISO 8601 text and ordering them means parsing them. {@code "P1M"} and
 * {@code "P30D"} are not lexically ordered, so comparing the raw strings would reject valid
 * refinements and admit invalid ones with equal confidence -- and would call a perfectly coherent
 * {@code { min: "P1M"  max: "P30D" }} empty. Parsing belongs to {@code DurationParser} in {@code
 * tson-compiler}, which this module cannot reach -- the same boundary {@link TextType#pattern} sits
 * behind. This is the one ordered family whose bounds neither check judges.
 */
@Typename(name = "duration_type")
public record DurationType(Optional<String> min, Optional<String> max) implements Atom {

    /** {@code duration => !duration_type {}} -- the unconstrained duration, §5.4's {@code !duration}. */
    public static final DurationType UNCONSTRAINED = new DurationType(Optional.empty(), Optional.empty());
}
