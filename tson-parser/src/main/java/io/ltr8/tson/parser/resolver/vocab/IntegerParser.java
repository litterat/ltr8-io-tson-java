package io.ltr8.tson.parser.resolver.vocab;

import io.ltr8.tson.parser.ast.TokenValue;
import io.ltr8.tson.parser.resolver.NumberForm;
import io.ltr8.tson.parser.resolver.NumberForms;
import io.ltr8.tson.parser.resolver.NumberGrammar;
import io.ltr8.tson.parser.resolver.NumberNarrowing;
import io.ltr8.tson.schema.meta.IntegerSize;
import io.ltr8.tson.schema.meta.IntegerType;

import java.math.BigInteger;

/**
 * Parses and validates against meta-kernel's {@code integer_type} constructor (§5.6's integer
 * atoms; not yet {@code number}, {@code float32}/{@code float64}, {@code rational}, or {@code
 * complex} -- those are backed by different constructors, {@code decimal_type}/{@code float_type}/
 * {@code rational_type}/{@code complex_type}, and are separate work). Holds a {@link
 * io.ltr8.tson.schema.meta.IntegerType} -- the pure constraint *values* {@code integer_type}
 * declares in the schema ({@code size}/{@code min}/{@code exclusive_min}/{@code max}/{@code
 * exclusive_max}/{@code multiple_of}), unchanged by this split -- rather than declaring those
 * fields itself; this class contributes only the parsing/validation *behavior* that consumes them.
 * Every other built-in vocabulary constructor to come follows the same split (a {@code schema.meta}
 * values class plus a {@code vocab} parser holding it), not a one-off for the integer family.
 *
 * <p>Accepts only the {@code integer}/{@code based-integer} grammar forms (§7.6) -- §5.6's table is
 * explicit that the integer atoms don't accept {@code float}/{@code special-value} tokens, unlike
 * {@code float32}/{@code float64}, which do.
 *
 * <p>Parsing and validation are kept as two visibly distinct steps, not one try/catch, because §5.2
 * requires the distinction to survive to error reporting: a token the grammar rejects is a parse
 * error, a parsed value outside the atom's range is a validation error.
 *
 * <p>One parse-then-validate pipeline ({@link #readBigInteger}) backs both {@link #read(TokenValue)}
 * (narrows to this atom's own natural host type -- {@link #hostType} when {@code size} is present,
 * {@link BigInteger} otherwise, so a fixed-width {@code int8} instance never hands back a {@code
 * BigInteger} for a value that fits a {@code Byte}) and {@link #read(TokenValue, Class)} (narrows
 * directly to a caller-supplied target via {@link NumberNarrowing}, e.g. {@code !uint8 42} into a
 * declared {@code int} field is one call, no intermediate {@code Number} created). Validation is
 * always against *this atom's own* declared constraint regardless of which entry point is used -- if
 * the target is narrower than what the atom actually guarantees (binding {@code !int32} to a {@code
 * short}), {@code NumberNarrowing} itself throws {@code ArithmeticException}, which is the caller's
 * problem to translate (mirroring how {@code tson-mapper}'s {@code AtomBinder} already handles the
 * same failure mode for untyped numbers, via the same shared narrowing code).
 */
public record IntegerParser(IntegerType constraints) implements AtomType<Number> {

    /** The kernel's unconstrained, arbitrary-precision {@code integer}. */
    public static final IntegerParser UNCONSTRAINED = new IntegerParser(IntegerType.UNCONSTRAINED);

    /** {@code int32 => !integer ^ { size: { bits: 32 signed: true } } } -- e.g. {@code new IntegerParser(new IntegerSize(32, true))}. */
    public IntegerParser(IntegerSize size) {
        this(new IntegerType(size));
    }

    /** {@code positive_integer => !integer ^ { min: 1 } }. */
    public static IntegerParser ofMin(BigInteger min) {
        return new IntegerParser(IntegerType.ofMin(min));
    }

    /** {@code negative_integer => !integer ^ { max: -1 } }. */
    public static IntegerParser ofMax(BigInteger max) {
        return new IntegerParser(IntegerType.ofMax(max));
    }

    @Override
    public Number read(TokenValue token) {
        Class<?> hostType = constraints.size().map(IntegerParser::hostType).orElse(BigInteger.class);
        return (Number) read(token, hostType);
    }

    @Override
    public Object read(TokenValue token, Class<?> target) {
        return NumberNarrowing.narrowIntegral(readBigInteger(token), target);
    }

    /** Plain decimal digits -- no width-dependent formatting quirk the way {@code FloatParser} has. */
    @Override
    public String write(Number value) {
        return value.toString();
    }

    private BigInteger readBigInteger(TokenValue token) {
        String text = token.text();
        NumberForm form = NumberGrammar.tryParse(text)
                .filter(f -> f instanceof NumberForm.IntegerForm || f instanceof NumberForm.BasedIntegerForm)
                .orElseThrow(() -> new AtomParseException(
                        "'" + text + "' is not a valid integer -- only integer and based-integer forms are accepted (§5.6)"));

        BigInteger value = NumberForms.toBigInteger(form);
        validate(value, text);
        return value;
    }

    private void validate(BigInteger value, String text) {
        constraints.size().ifPresent(s -> {
            BigInteger min = minValue(s);
            BigInteger max = maxValue(s);
            if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
                throw new AtomValidationException("'" + text + "' is out of range for a "
                        + (s.signed() ? "signed" : "unsigned") + " " + s.bits() + "-bit integer ["
                        + min + ", " + max + "]");
            }
        });
        constraints.min().ifPresent(m -> {
            if (value.compareTo(m) < 0) {
                throw new AtomValidationException("'" + text + "' is less than the minimum " + m);
            }
        });
        constraints.exclusiveMin().ifPresent(m -> {
            if (value.compareTo(m) <= 0) {
                throw new AtomValidationException("'" + text + "' must be strictly greater than " + m);
            }
        });
        constraints.max().ifPresent(m -> {
            if (value.compareTo(m) > 0) {
                throw new AtomValidationException("'" + text + "' is greater than the maximum " + m);
            }
        });
        constraints.exclusiveMax().ifPresent(m -> {
            if (value.compareTo(m) >= 0) {
                throw new AtomValidationException("'" + text + "' must be strictly less than " + m);
            }
        });
        constraints.multipleOf().ifPresent(m -> {
            if (value.remainder(m).signum() != 0) {
                throw new AtomValidationException("'" + text + "' is not a multiple of " + m);
            }
        });
    }

    private static BigInteger minValue(IntegerSize size) {
        int bits = size.bits().intValueExact();
        return size.signed() ? BigInteger.TWO.pow(bits - 1).negate() : BigInteger.ZERO;
    }

    private static BigInteger maxValue(IntegerSize size) {
        int bits = size.bits().intValueExact();
        return size.signed() ? BigInteger.TWO.pow(bits - 1).subtract(BigInteger.ONE) : BigInteger.TWO.pow(bits).subtract(BigInteger.ONE);
    }

    /**
     * The narrowest standard boxed integer type that holds every value this size admits --
     * {@code int8}/{@code int16}/{@code int32}/{@code int64} fit their same-named primitive
     * exactly, but an unsigned n-bit range needs the next-wider *signed* primitive (unsigned 8-bit
     * 0..255 overflows signed {@code byte}'s 127 max, so {@code uint8}'s natural host type is
     * {@code Short}, not {@code Byte}) since Java has no unsigned primitives. Widths beyond 64 bits,
     * signed or not, have no primitive that fits and fall through to {@link BigInteger}.
     */
    private static Class<?> hostType(IntegerSize size) {
        BigInteger min = minValue(size);
        BigInteger max = maxValue(size);
        if (fits(min, max, Byte.MIN_VALUE, Byte.MAX_VALUE)) {
            return Byte.class;
        }
        if (fits(min, max, Short.MIN_VALUE, Short.MAX_VALUE)) {
            return Short.class;
        }
        if (fits(min, max, Integer.MIN_VALUE, Integer.MAX_VALUE)) {
            return Integer.class;
        }
        if (fits(min, max, Long.MIN_VALUE, Long.MAX_VALUE)) {
            return Long.class;
        }
        return BigInteger.class;
    }

    private static boolean fits(BigInteger min, BigInteger max, long primitiveMin, long primitiveMax) {
        return min.compareTo(BigInteger.valueOf(primitiveMin)) >= 0 && max.compareTo(BigInteger.valueOf(primitiveMax)) <= 0;
    }
}
