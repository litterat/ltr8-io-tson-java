package io.ltr8.tson.mapper;

import io.ltr8.bind.DataBindException;
import io.ltr8.tson.parser.resolver.BaseValue;
import io.ltr8.tson.parser.resolver.NumberForm;
import io.ltr8.tson.parser.resolver.NumberForms;
import io.ltr8.tson.parser.resolver.NumberNarrowing;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Binds an identified {@link BaseValue} (§4's null/boolean/number/string classification) to a
 * concrete Java atom type -- the "binding" half of base type resolution that was deliberately
 * left undone when {@code BaseTypeResolver}/{@code NumberForm} were built: identification
 * determines which of the four grammar forms a token is and extracts its raw structural
 * components (sign, digit groups); this class is where those components finally become a
 * {@code long}, a {@code double}, a {@code BigInteger}, etc., driven by what the *target* Java
 * field actually declares.
 *
 * <p>Numbers are bound via an exact intermediate ({@link BigInteger} for integer/based-integer
 * forms, {@link BigDecimal} for float forms, extracted by {@link NumberForms}) and only narrowed
 * to the target type at the last step via {@link NumberNarrowing}, so {@code 0xFF} and {@code 255}
 * bind identically regardless of which representation was written (§4.3's equivalence requirement)
 * -- this is the layer that requirement actually needed, and {@code NumberForm} deliberately didn't
 * have it. Both {@code NumberForms} and {@code NumberNarrowing} live in {@code tson-parser}, not
 * here: the exact-intermediate step is target-type-agnostic, and the narrowing step is the exact
 * same target-matching logic the §5 built-in vocabulary's numeric atom types (also in {@code
 * tson-parser}) need for their own {@code read(TokenValue, Class)} -- one shared implementation
 * rather than one per caller.
 */
final class AtomBinder {

    private AtomBinder() {
    }

    static Object bind(BaseValue value, Class<?> target) throws DataBindException {
        return switch (value) {
            case BaseValue.NullValue ignored -> bindNull(target);
            case BaseValue.BooleanValue b -> bindBoolean(b.value(), target);
            case BaseValue.StringValue s -> bindString(s.text(), target);
            case BaseValue.NumberValue n -> bindNumber(n.form(), target);
        };
    }

    private static Object bindNull(Class<?> target) throws DataBindException {
        if (target.isPrimitive()) {
            throw new DataBindException("cannot bind null to primitive type " + target);
        }
        return null;
    }

    private static Object bindBoolean(boolean value, Class<?> target) throws DataBindException {
        if (target == boolean.class || target == Boolean.class) {
            return value;
        }
        throw new DataBindException("cannot bind a boolean value to " + target);
    }

    private static Object bindString(String text, Class<?> target) throws DataBindException {
        if (target == String.class) {
            return text;
        }
        if ((target == char.class || target == Character.class) && text.length() == 1) {
            return text.charAt(0);
        }
        throw new DataBindException("cannot bind a string value to " + target);
    }

    private static Object bindNumber(NumberForm form, Class<?> target) throws DataBindException {
        if (form instanceof NumberForm.SpecialValueForm special) {
            return bindSpecial(special, target);
        }
        if (form instanceof NumberForm.IntegerForm || form instanceof NumberForm.BasedIntegerForm) {
            return bindIntegral(NumberForms.toBigInteger(form), target);
        }
        if (form instanceof NumberForm.FloatForm floatForm) {
            return bindDecimal(NumberForms.toBigDecimal(floatForm), target);
        }
        throw new DataBindException("unrecognised number form: " + form);
    }

    private static Object bindSpecial(NumberForm.SpecialValueForm special, Class<?> target) throws DataBindException {
        double d = switch (special.kind()) {
            case NAN -> Double.NaN;
            case INFINITY -> special.sign().filter(s -> s == NumberForm.Sign.MINUS).isPresent()
                    ? Double.NEGATIVE_INFINITY
                    : Double.POSITIVE_INFINITY;
        };
        if (target == double.class || target == Double.class) {
            return d;
        }
        if (target == float.class || target == Float.class) {
            return (float) d;
        }
        throw new DataBindException("cannot bind '" + special.kind() + "' to " + target
                + " -- only float/double can represent it");
    }

    private static Object bindIntegral(BigInteger value, Class<?> target) throws DataBindException {
        try {
            return NumberNarrowing.narrowIntegral(value, target);
        } catch (ArithmeticException e) {
            throw new DataBindException(value + " does not fit in " + target.getSimpleName(), e);
        } catch (IllegalArgumentException e) {
            throw new DataBindException("cannot bind integer value " + value + " to " + target, e);
        }
    }

    private static Object bindDecimal(BigDecimal value, Class<?> target) throws DataBindException {
        try {
            return NumberNarrowing.narrowDecimal(value, target);
        } catch (IllegalArgumentException e) {
            throw new DataBindException(e.getMessage(), e);
        }
    }
}
