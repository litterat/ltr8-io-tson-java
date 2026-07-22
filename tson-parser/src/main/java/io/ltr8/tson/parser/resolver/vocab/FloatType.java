package io.ltr8.tson.parser.resolver.vocab;

import io.ltr8.tson.parser.ast.TokenValue;
import io.ltr8.tson.parser.resolver.NumberForm;
import io.ltr8.tson.parser.resolver.NumberGrammar;
import io.ltr8.tson.parser.resolver.NumberNarrowing;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * meta-kernel's {@code float_type} constructor (§5.6's {@code float32}/{@code float64} atoms --
 * SQL's approximate tier, IEEE 754-2019). Accepts {@code integer}/{@code float}/{@code hex-float}/
 * {@code special-value} forms (§7.6) -- the integer atoms' {@code based-integer} is *not* accepted
 * here, and {@code hex-float} *is*, the reverse of {@link IntegerType}'s and {@link DecimalType}'s
 * accepted-forms sets.
 *
 * <p>Unlike the exact types, there is no representation-equivalence requirement to preserve here --
 * the whole point of "approximate" is that the value is rounded onto the {@link Format}'s IEEE grid,
 * so precision loss is expected, not an error (§5.2, §5.6). That means, unlike {@link IntegerType}/
 * {@link DecimalType}, this doesn't route accepted forms through an exact intermediate at all --
 * {@code integer}/{@code float}/{@code hex-float} text all go straight to {@link Float#parseFloat}/
 * {@link Double#parseDouble}, both correctly-rounded per the JDK's own contract, always at *this*
 * atom's own {@link Format} precision, never through the other width first (parsing to {@code
 * double} then narrowing to {@code float} can occasionally differ from parsing to {@code float}
 * directly -- "double rounding"). Routing through {@link BigDecimal} first, as tried initially,
 * turned out to be actively wrong besides being unnecessary: {@code BigDecimal} has no negative-zero
 * concept ({@code new BigDecimal("-0.0").doubleValue()} silently loses the sign), while parsing the
 * text directly preserves it correctly.
 *
 * <p>{@code min}/{@code exclusiveMin}/{@code max}/{@code exclusiveMax} are checked against the
 * already-rounded value, not "before rounding" as meta-kernel's own doc for the numeric tiers
 * prescribes -- no built-in instance ({@code float32}/{@code float64} both leave every constraint at
 * its default) exercises this path today, so exact pre-rounding bound checking is deferred until a
 * schema (Part 2) actually needs it rather than implemented speculatively.
 */
public record FloatType(
        Format format,
        Optional<BigDecimal> min,
        Optional<BigDecimal> exclusiveMin,
        Optional<BigDecimal> max,
        Optional<BigDecimal> exclusiveMax,
        boolean allowNan,
        boolean allowInfinity,
        boolean allowSubnormal,
        boolean allowNegativeZero) implements AtomType<Number> {

    /** {@code ieee_format}'s two members §5.6 actually promotes to built-in annotations; meta.tn1 also defines BINARY16/128/256 and the decimal128-family formats, unused until a schema (Part 2) refines float_type with one of them. */
    public enum Format { BINARY32, BINARY64 }

    /** {@code float32 => !float_type { format: BINARY32 } }; {@code float64} is the BINARY64 twin -- every other field left at its default ({@code ~ true} / absent). */
    public static final FloatType FLOAT32 = unconstrained(Format.BINARY32);
    public static final FloatType FLOAT64 = unconstrained(Format.BINARY64);

    private static FloatType unconstrained(Format format) {
        return new FloatType(format, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                true, true, true, true);
    }

    public FloatType {
        if (min.isPresent() && exclusiveMin.isPresent()) {
            throw new IllegalArgumentException("min and exclusiveMin are mutually exclusive");
        }
        if (max.isPresent() && exclusiveMax.isPresent()) {
            throw new IllegalArgumentException("max and exclusiveMax are mutually exclusive");
        }
    }

    @Override
    public Number read(TokenValue token) {
        Class<?> natural = format == Format.BINARY32 ? Float.class : Double.class;
        return (Number) read(token, natural);
    }

    @Override
    public Object read(TokenValue token, Class<?> target) {
        double value = parseAtFormatPrecision(token.text());
        validate(value, token.text());
        return NumberNarrowing.narrowApproximate(value, target);
    }

    private double parseAtFormatPrecision(String text) {
        Optional<NumberForm> baseForm = NumberGrammar.tryParse(text);
        if (baseForm.isPresent()) {
            return switch (baseForm.get()) {
                case NumberForm.SpecialValueForm special -> specialToDouble(special);
                case NumberForm.IntegerForm ignored -> parseDirectly(text);
                case NumberForm.FloatForm ignored -> parseDirectly(text);
                case NumberForm.BasedIntegerForm ignored -> throw new AtomParseException("'" + text
                        + "' (a based-integer/hex/octal/binary whole number) is not accepted here -- only "
                        + "integer, float, hex-float, and special-value forms are (§5.6)");
            };
        }
        if (NumberGrammar.isHexFloat(text)) {
            return parseDirectly(text);
        }
        throw new AtomParseException(
                "'" + text + "' does not match integer, float, hex-float, or special-value (§5.6)");
    }

    /**
     * Parses {@code integer}/{@code float}/{@code hex-float} text directly at this atom's own
     * precision via the JDK's own (correctly-rounded) parser, rather than routing through an exact
     * {@link BigDecimal} intermediate first the way {@link IntegerType}/{@link DecimalType} do --
     * unlike those, there's no representation-equivalence requirement to preserve here (nothing
     * analogous to {@code 0xFF}/{@code 255} needing to bind identically), so there's no reason to.
     * Doing so would also be actively wrong for negative zero: {@link BigDecimal} has no negative-zero
     * concept ({@code new BigDecimal("-0.0").doubleValue()} loses the sign), while {@link
     * Double#parseDouble}/{@link Float#parseFloat} preserve it correctly, straight from the text.
     */
    private double parseDirectly(String text) {
        String cleaned = text.replace("_", "");
        return format == Format.BINARY32 ? Float.parseFloat(cleaned) : Double.parseDouble(cleaned);
    }

    private static double specialToDouble(NumberForm.SpecialValueForm special) {
        return switch (special.kind()) {
            case NAN -> Double.NaN;
            case INFINITY -> special.sign().filter(s -> s == NumberForm.Sign.MINUS).isPresent()
                    ? Double.NEGATIVE_INFINITY
                    : Double.POSITIVE_INFINITY;
        };
    }

    private void validate(double value, String text) {
        if (Double.isNaN(value)) {
            if (!allowNan) {
                throw new AtomValidationException("'" + text + "' is NaN, not permitted (allow_nan: false)");
            }
            return;
        }
        if (Double.isInfinite(value)) {
            if (!allowInfinity) {
                throw new AtomValidationException("'" + text + "' is infinite, not permitted (allow_infinity: false)");
            }
            return;
        }
        if (!allowNegativeZero && value == 0.0 && Double.doubleToRawLongBits(value) != 0L) {
            throw new AtomValidationException("'" + text + "' is negative zero, not permitted (allow_negative_zero: false)");
        }
        if (!allowSubnormal && isSubnormal(value)) {
            throw new AtomValidationException("'" + text + "' is a subnormal value, not permitted (allow_subnormal: false)");
        }
        min.ifPresent(m -> {
            if (value < m.doubleValue()) {
                throw new AtomValidationException("'" + text + "' is less than the minimum " + m);
            }
        });
        exclusiveMin.ifPresent(m -> {
            if (value <= m.doubleValue()) {
                throw new AtomValidationException("'" + text + "' must be strictly greater than " + m);
            }
        });
        max.ifPresent(m -> {
            if (value > m.doubleValue()) {
                throw new AtomValidationException("'" + text + "' is greater than the maximum " + m);
            }
        });
        exclusiveMax.ifPresent(m -> {
            if (value >= m.doubleValue()) {
                throw new AtomValidationException("'" + text + "' must be strictly less than " + m);
            }
        });
    }

    private boolean isSubnormal(double value) {
        double abs = Math.abs(value);
        if (abs == 0.0) {
            return false;
        }
        return format == Format.BINARY32 ? abs < Float.MIN_NORMAL : abs < Double.MIN_NORMAL;
    }
}
