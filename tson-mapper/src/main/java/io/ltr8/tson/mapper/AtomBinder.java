package io.ltr8.tson.mapper;

import io.ltr8.bind.DataBindException;
import io.ltr8.tson.parser.resolver.BaseValue;
import io.ltr8.tson.parser.resolver.NumberForm;

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
 * forms, {@link BigDecimal} for float forms) and only narrowed to the target type at the last
 * step, so {@code 0xFF} and {@code 255} bind identically regardless of which representation was
 * written (§4.3's equivalence requirement) -- this is the layer that requirement actually needed,
 * and {@code NumberForm} deliberately didn't have it.
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
            return bindIntegral(toBigInteger(form), target);
        }
        if (form instanceof NumberForm.FloatForm floatForm) {
            return bindDecimal(toBigDecimal(floatForm), target);
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

    private static BigInteger toBigInteger(NumberForm form) {
        java.util.Optional<NumberForm.Sign> sign;
        String digits;
        int radix;
        if (form instanceof NumberForm.IntegerForm f) {
            sign = f.sign();
            digits = f.digits();
            radix = 10;
        } else if (form instanceof NumberForm.BasedIntegerForm f) {
            sign = f.sign();
            digits = f.digits();
            radix = switch (f.radix()) {
                case HEX -> 16;
                case OCTAL -> 8;
                case BINARY -> 2;
            };
        } else {
            throw new IllegalArgumentException("not an integer form: " + form);
        }
        BigInteger value = new BigInteger(digits.replace("_", ""), radix);
        return sign.filter(s -> s == NumberForm.Sign.MINUS).isPresent() ? value.negate() : value;
    }

    private static BigDecimal toBigDecimal(NumberForm.FloatForm f) {
        StringBuilder sb = new StringBuilder();
        if (f.sign().filter(s -> s == NumberForm.Sign.MINUS).isPresent()) {
            sb.append('-');
        }
        sb.append(f.integerPart().map(s -> s.replace("_", "")).orElse("0"));
        f.fractionDigits().ifPresent(frac -> sb.append('.').append(frac.replace("_", "")));
        f.exponent().ifPresent(exp -> {
            sb.append('e');
            if (exp.sign().filter(s -> s == NumberForm.Sign.MINUS).isPresent()) {
                sb.append('-');
            }
            sb.append(exp.digits().replace("_", ""));
        });
        return new BigDecimal(sb.toString());
    }

    private static Object bindIntegral(BigInteger value, Class<?> target) throws DataBindException {
        try {
            if (target == int.class || target == Integer.class) {
                return value.intValueExact();
            }
            if (target == long.class || target == Long.class) {
                return value.longValueExact();
            }
            if (target == short.class || target == Short.class) {
                return value.shortValueExact();
            }
            if (target == byte.class || target == Byte.class) {
                return value.byteValueExact();
            }
        } catch (ArithmeticException e) {
            throw new DataBindException(value + " does not fit in " + target.getSimpleName(), e);
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
        throw new DataBindException("cannot bind integer value " + value + " to " + target);
    }

    private static Object bindDecimal(BigDecimal value, Class<?> target) throws DataBindException {
        if (target == float.class || target == Float.class) {
            return value.floatValue();
        }
        if (target == double.class || target == Double.class) {
            return value.doubleValue();
        }
        if (target == BigDecimal.class) {
            return value;
        }
        throw new DataBindException("cannot bind float value " + value + " to " + target
                + " -- write it as an integer token if an exact integral type is intended");
    }
}
