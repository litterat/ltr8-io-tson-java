package io.ltr8.tson.parser.resolver.vocab;

import io.ltr8.tson.parser.ast.TokenValue;
import io.ltr8.tson.parser.resolver.NumberForm;
import io.ltr8.tson.parser.resolver.NumberForms;
import io.ltr8.tson.parser.resolver.NumberGrammar;
import io.ltr8.tson.parser.resolver.NumberNarrowing;

import java.math.BigInteger;
import java.util.Optional;

/**
 * meta-kernel's {@code integer_type} constructor (§5.6's integer atoms; not yet {@code number},
 * {@code float32}/{@code float64}, {@code rational}, or {@code complex} -- those are backed by
 * different constructors, {@code decimal_type}/{@code float_type}/{@code rational_type}/{@code
 * complex_type}, and are separate work). A record, not a type paired with a separate constraints
 * class -- {@code integer_type} is one flat entity in the schema ({@code size}/{@code min}/{@code
 * exclusive_min}/{@code max}/{@code exclusive_max}/{@code multiple_of} are its own fields, not a
 * nested constraints record), and every other built-in vocabulary constructor to come is the same
 * shape, so this is the pattern to repeat rather than an {@code IntegerType} + {@code
 * IntegerConstraints} pair.
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
 * (narrows to this atom's own natural host type -- {@link IntegerSize#hostType()} when {@link
 * #size} is present, {@link BigInteger} otherwise, so a fixed-width {@code int8} instance never
 * hands back a {@code BigInteger} for a value that fits a {@code Byte}) and {@link
 * #read(TokenValue, Class)} (narrows directly to a caller-supplied target via {@link
 * NumberNarrowing}, e.g. {@code !uint8 42} into a declared {@code int} field is one call, no
 * intermediate {@code Number} created). Validation is always against *this atom's own* declared
 * constraint regardless of which entry point is used -- if the target is narrower than what the
 * atom actually guarantees (binding {@code !int32} to a {@code short}), {@code NumberNarrowing}
 * itself throws {@code ArithmeticException}, which is the caller's problem to translate (mirroring
 * how {@code tson-mapper}'s {@code AtomBinder} already handles the same failure mode for untyped
 * numbers, via the same shared narrowing code).
 */
public record IntegerType(
        Optional<IntegerSize> size,
        Optional<BigInteger> min,
        Optional<BigInteger> exclusiveMin,
        Optional<BigInteger> max,
        Optional<BigInteger> exclusiveMax,
        Optional<BigInteger> multipleOf) implements AtomType<Number> {

    /** The kernel's unconstrained, arbitrary-precision {@code integer} -- no built-in annotation names it directly (§5.6 has no {@code !integer}; {@code !number} fills that role via {@code decimal_type}), but every fixed-width/refined instance is built from it. */
    public static final IntegerType UNCONSTRAINED = new IntegerType(
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    public IntegerType {
        if (min.isPresent() && exclusiveMin.isPresent()) {
            throw new IllegalArgumentException("min and exclusiveMin are mutually exclusive");
        }
        if (max.isPresent() && exclusiveMax.isPresent()) {
            throw new IllegalArgumentException("max and exclusiveMax are mutually exclusive");
        }
    }

    /** {@code int32 => !integer ^ { size: { bits: 32 signed: true } } } -- e.g. {@code new IntegerType(new IntegerSize(32, true))}. */
    public IntegerType(IntegerSize size) {
        this(Optional.of(size), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** {@code positive_integer => !integer ^ { min: 1 } }. */
    public static IntegerType ofMin(BigInteger min) {
        return new IntegerType(Optional.empty(), Optional.of(min), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** {@code negative_integer => !integer ^ { max: -1 } }. */
    public static IntegerType ofMax(BigInteger max) {
        return new IntegerType(Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(max), Optional.empty(), Optional.empty());
    }

    @Override
    public Number read(TokenValue token) {
        Class<?> hostType = size.map(IntegerSize::hostType).orElse(BigInteger.class);
        return (Number) read(token, hostType);
    }

    @Override
    public Object read(TokenValue token, Class<?> target) {
        return NumberNarrowing.narrowIntegral(readBigInteger(token), target);
    }

    /** Plain decimal digits -- no width-dependent formatting quirk the way {@link FloatType} has. */
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
        size.ifPresent(s -> {
            if (value.compareTo(s.minValue()) < 0 || value.compareTo(s.maxValue()) > 0) {
                throw new AtomValidationException("'" + text + "' is out of range for a "
                        + (s.signed() ? "signed" : "unsigned") + " " + s.bits() + "-bit integer ["
                        + s.minValue() + ", " + s.maxValue() + "]");
            }
        });
        min.ifPresent(m -> {
            if (value.compareTo(m) < 0) {
                throw new AtomValidationException("'" + text + "' is less than the minimum " + m);
            }
        });
        exclusiveMin.ifPresent(m -> {
            if (value.compareTo(m) <= 0) {
                throw new AtomValidationException("'" + text + "' must be strictly greater than " + m);
            }
        });
        max.ifPresent(m -> {
            if (value.compareTo(m) > 0) {
                throw new AtomValidationException("'" + text + "' is greater than the maximum " + m);
            }
        });
        exclusiveMax.ifPresent(m -> {
            if (value.compareTo(m) >= 0) {
                throw new AtomValidationException("'" + text + "' must be strictly less than " + m);
            }
        });
        multipleOf.ifPresent(m -> {
            if (value.remainder(m).signum() != 0) {
                throw new AtomValidationException("'" + text + "' is not a multiple of " + m);
            }
        });
    }
}
