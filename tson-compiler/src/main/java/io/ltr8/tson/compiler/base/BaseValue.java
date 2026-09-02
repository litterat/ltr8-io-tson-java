package io.ltr8.tson.compiler.base;

/**
 * The result of base type resolution (§4): a token's identified base type. Identification only --
 * {@link NumberValue} wraps a {@link NumberForm} (the recognized grammar shape), not a bound Java
 * numeric type. See {@link NumberForm}'s Javadoc for why binding is a separate, later step.
 *
 * <p>{@link AbsentValue} is the one member {@link BaseTypeResolver} never returns. §4 resolves three
 * classes and absence is not one of them: {@code _} is lexical, arriving as its own token type and its own
 * event, and never as a token this resolver sees. It is a member here because binding an identified value
 * to a host type is one switch ({@code AtomBinder.bind}), and a schemaless bind reaching {@code _} needs a
 * way into it; the alternative is a second entry point for absence alone.
 */
public sealed interface BaseValue
        permits BaseValue.AbsentValue, BaseValue.BooleanValue, BaseValue.NumberValue, BaseValue.StringValue {

    /** The absent sentinel {@code _} (§2.9): no value occupies the position. Never produced by base resolution. */
    record AbsentValue() implements BaseValue {}

    /** {@code true} or {@code false} (§4.2). */
    record BooleanValue(boolean value) implements BaseValue {}

    /** An unquoted token whose complete text matched the {@code number} production (§4.3). */
    record NumberValue(NumberForm form) implements BaseValue {}

    /** Every quoted token, and every unquoted token that isn't boolean or a number (§4.4). */
    record StringValue(String text) implements BaseValue {}
}
