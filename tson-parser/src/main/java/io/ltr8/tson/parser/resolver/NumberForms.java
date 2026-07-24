package io.ltr8.tson.parser.resolver;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Extracts the exact magnitude a {@link NumberForm} denotes, as {@link BigInteger}/
 * {@link BigDecimal} -- the one canonicalization step every consumer of a recognized number form
 * needs regardless of what host type it eventually binds to (§4.3's required equivalence between
 * representations, {@code 255}/{@code 0xFF}, holds at this exact-intermediate step and nowhere
 * else). Still not a Java numeric type in the narrowed sense ({@code int}/{@code long}/{@code
 * float}/{@code double}) -- that choice stays with each consumer (e.g. {@code
 * io.ltr8.tson.parser.mapper}'s {@code AtomBinder} narrowing to a target class, or this package's
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
}
