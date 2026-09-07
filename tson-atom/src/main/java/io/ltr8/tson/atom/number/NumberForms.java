package io.ltr8.tson.atom.number;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Extracts the exact magnitude a {@link NumberForm} denotes, as {@link BigInteger}/
 * {@link BigDecimal} -- the one canonicalization step every consumer of a recognized number form
 * needs regardless of what host type it eventually binds to (§4.3's required equivalence between
 * representations, {@code 255}/{@code 0xFF}, holds at this exact-intermediate step and nowhere
 * else). Still not a Java numeric type in the narrowed sense ({@code int}/{@code long}/{@code
 * float}/{@code double}) -- that choice stays with each consumer (e.g. {@code
 * TsonObjectReader}'s {@code AtomBinder} narrowing to a target class, or this package's
 * {@code vocab} atom types
 * range-checking against a built-in vocabulary constraint) -- but unlike {@link NumberForm} itself,
 * this class does combine digit groups into one exact value, since every consumer needs that same
 * value and duplicating the combination logic per consumer was the alternative.
 */
public final class NumberForms {

    private NumberForms() {
    }

    /** {@code form} must be an {@link NumberForm.IntegerForm} or {@link NumberForm.BasedIntegerForm}. */
    public static BigInteger toBigInteger(NumberForm form) {
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

    public static BigDecimal toBigDecimal(NumberForm.FloatForm f) {
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

    /**
     * {@code value} as the token text that reads back to it -- [TSON-DATA] §5.3's own spellings for the
     * three IEEE special values ({@code .nan}, {@code +.inf}, {@code -.inf}), which have no Java
     * counterpart: {@code Double#toString} prints {@code NaN}/{@code Infinity}/{@code -Infinity}, none of
     * which is a TSON token.
     *
     * <p>A {@code Float} is formatted via {@link Float#toString()} directly rather than widened to
     * {@code double} first -- widening introduces noise digits {@code Double#toString} would then print
     * correctly for the widened value and wrongly for the original.
     *
     * <p>It is the number grammar's, not any one atom's: the {@code float} family's writer and the
     * schemaless writer that frames a bare {@code Double} both need the same spelling, and two copies of a
     * three-case table is how they come to disagree.
     */
    public static String floatToken(Number value) {
        if (value instanceof Float f) {
            if (Float.isNaN(f)) {
                return ".nan";
            }
            if (Float.isInfinite(f)) {
                return f > 0 ? "+.inf" : "-.inf";
            }
            return Float.toString(f);
        }
        double d = value.doubleValue();
        if (Double.isNaN(d)) {
            return ".nan";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "+.inf" : "-.inf";
        }
        return Double.toString(d);
    }
}
