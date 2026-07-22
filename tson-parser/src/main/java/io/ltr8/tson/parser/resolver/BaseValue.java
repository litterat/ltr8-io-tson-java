package io.ltr8.tson.parser.resolver;

/**
 * The result of base type resolution (§4): a token's identified base type. Identification only --
 * {@link NumberValue} wraps a {@link NumberForm} (the recognized grammar shape), not a bound Java
 * numeric type. See {@link NumberForm}'s Javadoc for why binding is a separate, later step.
 */
public sealed interface BaseValue
        permits BaseValue.NullValue, BaseValue.BooleanValue, BaseValue.NumberValue, BaseValue.StringValue {

    /** The token {@code null} (§4.1) -- distinct from the absent sentinel {@code _} (§2.9). */
    record NullValue() implements BaseValue {}

    /** {@code true} or {@code false} (§4.2). */
    record BooleanValue(boolean value) implements BaseValue {}

    /** An unquoted token whose complete text matched the {@code number} production (§4.3). */
    record NumberValue(NumberForm form) implements BaseValue {}

    /** Every quoted token, and every unquoted token that isn't {@code null}/boolean/a number (§4.4). */
    record StringValue(String text) implements BaseValue {}
}
