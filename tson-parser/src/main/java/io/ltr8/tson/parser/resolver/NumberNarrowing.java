package io.ltr8.tson.parser.resolver;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Narrows an exact intermediate value ({@link BigInteger}/{@link BigDecimal}, from {@link
 * NumberForms}) to a caller-supplied target representation. The one place this decision is made --
 * both {@code tson-mapper}'s {@code AtomBinder} (untyped §4 numbers, where the target field is the
 * only source of width information) and {@code resolver.vocab}'s numeric atom types (§5 built-in
 * vocabulary, e.g. {@link io.ltr8.tson.parser.resolver.vocab.IntegerType}, where the atom's own
 * declared width narrows first and this only adapts the already-validated value to whatever
 * representation the caller asked for) need the identical target-matching logic, so it lives here
 * once rather than once per caller.
 *
 * <p>Throws {@link ArithmeticException} when {@code target} can't hold the value exactly (e.g.
 * narrowing 200 to {@code byte}), and {@link IllegalArgumentException} when {@code target} isn't a
 * supported numeric representation at all -- deliberately not this module's own exception types,
 * since "my target is too narrow/wrong" is the caller's problem to classify and report, not a §5.2
 * parse/validation concern about the atom itself.
 */
public final class NumberNarrowing {

    private NumberNarrowing() {
    }

    public static Object narrowIntegral(BigInteger value, Class<?> target) {
        if (target == byte.class || target == Byte.class) {
            return value.byteValueExact();
        }
        if (target == short.class || target == Short.class) {
            return value.shortValueExact();
        }
        if (target == int.class || target == Integer.class) {
            return value.intValueExact();
        }
        if (target == long.class || target == Long.class) {
            return value.longValueExact();
        }
        if (target == BigInteger.class) {
            return value;
        }
        if (target == float.class || target == Float.class) {
            return value.floatValue();
        }
        if (target == double.class || target == Double.class) {
            return value.doubleValue();
        }
        if (target == BigDecimal.class) {
            return new BigDecimal(value);
        }
        throw new IllegalArgumentException("cannot represent an integer value as " + target);
    }

    public static Object narrowDecimal(BigDecimal value, Class<?> target) {
        if (target == float.class || target == Float.class) {
            return value.floatValue();
        }
        if (target == double.class || target == Double.class) {
            return value.doubleValue();
        }
        if (target == BigDecimal.class) {
            return value;
        }
        throw new IllegalArgumentException("cannot represent a float value as " + target
                + " -- write it as an integer token if an exact integral type is intended");
    }
}
