package io.ltr8.tson.compiler.atom;

import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.base.NumberForm;
import io.ltr8.tson.compiler.base.NumberForms;
import io.ltr8.tson.compiler.base.NumberGrammar;
import io.ltr8.tson.compiler.base.NumberNarrowing;
import io.ltr8.tson.schema.meta.IntegerSize;
import io.ltr8.tson.schema.meta.IntegerType;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

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
 * values class plus a {@code vocab} compiler holding it), not a one-off for the integer family.
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
 * problem to translate (mirroring how {@code TsonObjectReader}'s {@code AtomBinder} already handles the
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
                        "'" + text + "' is not a valid integer -- only integer and based-integer forms are accepted (§5.6)",
                        "an integer or based-integer form"));

        BigInteger value = NumberForms.toBigInteger(form);
        validate(value, text);
        return value;
    }

    private void validate(BigInteger value, String text) {
        constraints.size().ifPresent(s -> {
            BigInteger min = bounds(s)[0];
            BigInteger max = bounds(s)[1];
            if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
                throw new AtomValidationException("'" + text + "' is out of range for a "
                        + (s.signed() ? "signed" : "unsigned") + " " + s.bits() + "-bit integer ["
                        + min + ", " + max + "]", ">= " + min + " and <= " + max);
            }
        });
        constraints.min().ifPresent(m -> {
            if (value.compareTo(m) < 0) {
                throw new AtomValidationException("'" + text + "' is less than the minimum " + m, ">= " + m);
            }
        });
        constraints.exclusiveMin().ifPresent(m -> {
            if (value.compareTo(m) <= 0) {
                throw new AtomValidationException("'" + text + "' must be strictly greater than " + m, "> " + m);
            }
        });
        constraints.max().ifPresent(m -> {
            if (value.compareTo(m) > 0) {
                throw new AtomValidationException("'" + text + "' is greater than the maximum " + m, "<= " + m);
            }
        });
        constraints.exclusiveMax().ifPresent(m -> {
            if (value.compareTo(m) >= 0) {
                throw new AtomValidationException("'" + text + "' must be strictly less than " + m, "< " + m);
            }
        });
        constraints.multipleOf().ifPresent(m -> {
            if (value.remainder(m).signum() != 0) {
                throw new AtomValidationException("'" + text + "' is not a multiple of " + m, "a multiple of " + m);
            }
        });
    }

    /**
     * {@code {min, max}} for {@code size} -- from {@link #STANDARD_BOUNDS} where the width is one the
     * vocabulary declares, computed where it is not.
     */
    private static BigInteger[] bounds(IntegerSize size) {
        BigInteger[] standard = STANDARD_BOUNDS.get(size);
        return standard != null ? standard : new BigInteger[] {minValue(size), maxValue(size)};
    }

    /**
     * The width bounds precomputed, because they are constants of the type and were being rebuilt on every
     * value validated: {@code BigInteger.TWO.pow(bits)} per bound per integer read, which profiling put at
     * 5% of everything a bind read allocated. Keyed by {@link IntegerSize} and covering the ladder core.tn
     * declares; an exotic width falls through to {@link #bounds} computing its own, so nothing here grows
     * with what a schema declares.
     */
    private static final Map<IntegerSize, BigInteger[]> STANDARD_BOUNDS = standardBounds();

    private static Map<IntegerSize, BigInteger[]> standardBounds() {
        Map<IntegerSize, BigInteger[]> table = new HashMap<>();
        for (int bits : new int[] {8, 16, 32, 64, 128}) {
            for (boolean signed : new boolean[] {true, false}) {
                IntegerSize size = new IntegerSize(BigInteger.valueOf(bits), signed);
                table.put(size, new BigInteger[] {minValue(size), maxValue(size)});
            }
        }
        return Map.copyOf(table);
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
