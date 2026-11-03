package io.ltr8.tson.atom.parser;

import java.util.Optional;

import io.ltr8.tson.atom.AtomParseException;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.AtomValidationException;
import io.ltr8.tson.atom.number.NumberForm;
import io.ltr8.tson.atom.number.NumberForms;
import io.ltr8.tson.atom.number.NumberGrammar;
import io.ltr8.tson.atom.number.NumberNarrowing;
import io.ltr8.tson.schema.numeric.IntegerSize;
import io.ltr8.tson.schema.numeric.IntegerType;
import java.math.BigInteger;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Parses and validates against the kernel's {@code integer_type} constructor (§5.6's integer
 * atoms; not yet {@code number}, {@code float32}/{@code float64}, {@code rational}, or {@code
 * complex} -- those are backed by different constructors, {@code decimal_type}/{@code float_type}/
 * {@code rational_type}/{@code complex_type}, and are separate work). Holds a {@link
 * io.ltr8.tson.schema.numeric.IntegerType} -- the pure constraint *values* {@code integer_type}
 * declares in the schema ({@code size}/{@code min}/{@code exclusive_min}/{@code max}/{@code
 * exclusive_max}/{@code multiple_of}/{@code members}), unchanged by this split -- rather than declaring
 * those fields itself; this class contributes only the parsing/validation *behavior* that consumes them.
 * Every other built-in vocabulary constructor to come follows the same split (a schema-model
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
 * <p>One parse-then-validate pipeline ({@link #readBigInteger}) backs both {@link #read(String)}
 * (narrows to this atom's own natural host type -- {@link #hostType} when {@code size} is present,
 * {@link BigInteger} otherwise, so a fixed-width {@code int8} instance never hands back a {@code
 * BigInteger} for a value that fits a {@code Byte}) and {@link #boundTo(Class)} (narrows
 * directly to a caller-supplied target via {@link NumberNarrowing}, e.g. {@code !uint8 42} into a
 * declared {@code int} field is one call, no intermediate {@code Number} created). Validation is
 * always against *this atom's own* declared constraint regardless of which entry point is used -- if
 * the target is narrower than what the atom actually guarantees (binding {@code !int32} to a {@code
 * short}), {@code NumberNarrowing} itself throws {@code ArithmeticException}, which is the caller's
 * problem to translate (mirroring how {@code TsonObjectReader}'s {@code AtomBinder} already handles the
 * same failure mode for untyped numbers, via the same shared narrowing code).
 *
 * <p>A width's range is judged by bit length ({@link IntegerSize#admits}) and never materialised, so a read
 * under any {@code bits} a schema states is exact and allocates no bound: a {@code size} of three billion bits
 * reads every value its range holds, into a {@link BigInteger}.
 */
public record IntegerParser(IntegerType constraints) implements AtomTypeParser<Number> {

    /** The kernel's unconstrained, arbitrary-precision {@code integer}. */
    public static final IntegerParser UNCONSTRAINED = new IntegerParser(IntegerType.UNCONSTRAINED);

    /** {@code int32 => !integer ^ { size: { bits: 32 signed: true } } } -- e.g. {@code new IntegerParser(new IntegerSize(32, true))}. */
    public IntegerParser(IntegerSize size) {
        this(new IntegerType(size));
    }

    /** {@code !integer ^ { min: 1 } } -- a lower bound and no width. */
    public static IntegerParser ofMin(BigInteger min) {
        return new IntegerParser(IntegerType.ofMin(min));
    }

    /** {@code !integer ^ { max: -1 } } -- an upper bound and no width. */
    public static IntegerParser ofMax(BigInteger max) {
        return new IntegerParser(IntegerType.ofMax(max));
    }

    @Override
    public Number read(String text) {
        return (Number) narrowTo(text, constraints.size().isPresent()
                ? hostType(constraints.size().get())
                : BigInteger.class);
    }

    /** This family's own narrowing, private now that {@code AtomType} has no target-aware read. */
    private Object narrowTo(String text, Class<?> target) {
        return NumberNarrowing.narrowIntegral(readBigInteger(text), target);
    }

    /** Plain decimal digits -- no width-dependent formatting quirk the way {@code FloatParser} has. */
    @Override
    public String write(Number value) {
        return value.toString();
    }

    private BigInteger readBigInteger(String text) {
        NumberForm form = NumberGrammar.tryParse(text)
                .filter(f -> f instanceof NumberForm.IntegerForm || f instanceof NumberForm.BasedIntegerForm)
                .orElseThrow(() -> new AtomParseException(
                        "'" + text + "' is not a valid integer -- only integer and based-integer forms are accepted (§5.6)",
                        "an integer or based-integer form"));

        BigInteger value = NumberForms.toBigInteger(form);
        validate(value, text);
        return value;
    }

    /**
     * The constraints in turn, each tested with {@code isPresent} rather than {@code ifPresent}: a capturing
     * lambda per constraint per value is an allocation on the read path of every integer read, and the tests
     * are the same either way.
     */
    private void validate(BigInteger value, String text) {
        if (constraints.size().isPresent() && !constraints.size().get().admits(value)) {
            IntegerSize s = constraints.size().get();
            String[] range = range(s);
            throw new AtomValidationException("'" + text + "' is out of range for a " + s.describe() + " integer ["
                    + range[0] + ", " + range[1] + "]", ">= " + range[0] + " and <= " + range[1]);
        }
        if (constraints.min().isPresent() && value.compareTo(constraints.min().get()) < 0) {
            BigInteger m = constraints.min().get();
            throw new AtomValidationException("'" + text + "' is less than the minimum " + m, ">= " + m);
        }
        if (constraints.exclusiveMin().isPresent() && value.compareTo(constraints.exclusiveMin().get()) <= 0) {
            BigInteger m = constraints.exclusiveMin().get();
            throw new AtomValidationException("'" + text + "' must be strictly greater than " + m, "> " + m);
        }
        if (constraints.max().isPresent() && value.compareTo(constraints.max().get()) > 0) {
            BigInteger m = constraints.max().get();
            throw new AtomValidationException("'" + text + "' is greater than the maximum " + m, "<= " + m);
        }
        if (constraints.exclusiveMax().isPresent() && value.compareTo(constraints.exclusiveMax().get()) >= 0) {
            BigInteger m = constraints.exclusiveMax().get();
            throw new AtomValidationException("'" + text + "' must be strictly less than " + m, "< " + m);
        }
        if (constraints.multipleOf().isPresent()
                && value.remainder(constraints.multipleOf().get()).signum() != 0) {
            BigInteger m = constraints.multipleOf().get();
            throw new AtomValidationException("'" + text + "' is not a multiple of " + m, "a multiple of " + m);
        }
        // The member set is compared on the decoded value, which is [TSON-DATA] §4.3's identity for this
        // family: 0x50 and 80 are one member, having reduced to one BigInteger before ever reaching here.
        if (constraints.members().isPresent() && !constraints.members().get().contains(value)) {
            List<BigInteger> members = constraints.members().get();
            throw new AtomValidationException(
                    "'" + text + "' is not a member of this type -- expected one of " + members,
                    "one of (" + members.stream().map(BigInteger::toString).collect(Collectors.joining(", ")) + ")");
        }
    }

    /**
     * {@code {min, max}} for {@code size}, as a refusal states them: a value's range is judged by
     * {@link IntegerSize#admits}, never by these. A width up to {@link #SPELLED_BITS} is spelled out, a wider
     * one by its power of two, so a refusal under any {@code bits} costs no more than its message.
     */
    private static String[] range(IntegerSize size) {
        BigInteger bits = size.bits();
        if (bits.compareTo(BigInteger.valueOf(SPELLED_BITS)) <= 0) {
            int n = bits.intValue();
            return size.signed()
                    ? new String[] {BigInteger.ONE.shiftLeft(n - 1).negate().toString(),
                            BigInteger.ONE.shiftLeft(n - 1).subtract(BigInteger.ONE).toString()}
                    : new String[] {"0", BigInteger.ONE.shiftLeft(n).subtract(BigInteger.ONE).toString()};
        }
        return size.signed()
                ? new String[] {"-2^" + bits.subtract(BigInteger.ONE), "2^" + bits.subtract(BigInteger.ONE) + " - 1"}
                : new String[] {"0", "2^" + bits + " - 1"};
    }

    /** The widest width {@link #range} spells digit by digit: 256 bits is 78 digits. */
    private static final int SPELLED_BITS = 256;

    /**
     * The narrowest standard boxed integer type that holds every value this size admits --
     * {@code int8}/{@code int16}/{@code int32}/{@code int64} fit their same-named primitive
     * exactly, but an unsigned n-bit range needs the next-wider *signed* primitive (unsigned 8-bit
     * 0..255 overflows signed {@code byte}'s 127 max, so {@code uint8}'s natural host type is
     * {@code Short}, not {@code Byte}) since Java has no unsigned primitives. Widths beyond 64 bits,
     * signed or not, have no primitive that fits and fall through to {@link BigInteger}.
     */
    private static Class<?> hostType(IntegerSize size) {
        if (size.within(INT8)) {
            return Byte.class;
        }
        if (size.within(INT16)) {
            return Short.class;
        }
        if (size.within(INT32)) {
            return Integer.class;
        }
        if (size.within(INT64)) {
            return Long.class;
        }
        return BigInteger.class;
    }

    /** The signed widths of Java's integral primitives, which {@link #hostType} fits a width into. */
    private static final IntegerSize INT8 = new IntegerSize(Byte.SIZE, true);
    private static final IntegerSize INT16 = new IntegerSize(Short.SIZE, true);
    private static final IntegerSize INT32 = new IntegerSize(Integer.SIZE, true);
    private static final IntegerSize INT64 = new IntegerSize(Long.SIZE, true);

    /**
     * The numeric targets narrowsIntegral reaches, and no text one: a number's wire form is a number,
     * so handing back its spelling would be a different reading rather than the same value.
     */
    @Override
    public Optional<AtomType<?>> boundTo(Class<?> target) {
        if (!NumberNarrowing.narrowsIntegral(target)) {
            return Optional.empty();
        }
        return bound(text -> narrowTo(text, target), value -> write((Number) value));
    }

}
