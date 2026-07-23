package io.ltr8.tson.parser.resolver.vocab;

import io.ltr8.tson.parser.ast.TokenForm;
import io.ltr8.tson.parser.ast.TokenValue;
import io.ltr8.tson.schema.meta.FloatType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FloatParserTest {

    private static TokenValue token(String text) {
        return new TokenValue(text, TokenForm.UNQUOTED);
    }

    // ── §7.6 form acceptance (§5.6: "integer / float / hex-float / special-value") ─────────────

    @Test
    void acceptsPlainIntegerToken() {
        assertEquals(42.0, FloatParser.FLOAT64.read(token("42"), double.class));
    }

    @Test
    void acceptsDecimalFloat() {
        assertEquals(199.90, (double) FloatParser.FLOAT64.read(token("199.90"), double.class), 0.0001);
    }

    @Test
    void acceptsHexFloat() {
        // 0x1.8p3 = 1.5 * 2^3 = 12.0
        assertEquals(12.0, (double) FloatParser.FLOAT64.read(token("0x1.8p3"), double.class), 0.0);
    }

    @Test
    void acceptsHexFloatWithNoIntegerPart() {
        // 0x.8p1 = 0.5 * 2^1 = 1.0
        assertEquals(1.0, (double) FloatParser.FLOAT64.read(token("0x.8p1"), double.class), 0.0);
    }

    @Test
    void acceptsHexFloatWithUnderscoreSeparators() {
        // 0x1_8.0p0: digits "1_8" strip to "18" (hex) = 24.0, * 2^0 = 24.0
        assertEquals(24.0, (double) FloatParser.FLOAT64.read(token("0x1_8.0p0"), double.class), 0.0);
    }

    @Test
    void basedIntegerFormIsRejected() {
        // §5.6: float atoms accept integer/float/hex-float/special-value, not based-integer.
        assertThrows(AtomParseException.class, () -> FloatParser.FLOAT64.read(token("0xFF")));
    }

    @Test
    void nonNumericTokenIsAParseError() {
        assertThrows(AtomParseException.class, () -> FloatParser.FLOAT64.read(token("twelve")));
    }

    // ── Special values (§5.6: "the float atoms... give the special values IEEE 754-2019 semantics") ──

    @Test
    void nanBindsToDouble() {
        assertTrue(Double.isNaN((double) FloatParser.FLOAT64.read(token(".nan"), double.class)));
    }

    @Test
    void positiveAndNegativeInfinityBind() {
        assertEquals(Double.POSITIVE_INFINITY, FloatParser.FLOAT64.read(token(".inf"), double.class));
        assertEquals(Double.NEGATIVE_INFINITY, FloatParser.FLOAT64.read(token("-.inf"), double.class));
    }

    @Test
    void nanRejectedWhenAllowNanFalse() {
        FloatParser strict = new FloatParser(FloatType.Format.BINARY64, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), false, true, true, true);
        assertThrows(AtomValidationException.class, () -> strict.read(token(".nan")));
    }

    @Test
    void infinityRejectedWhenAllowInfinityFalse() {
        FloatParser strict = new FloatParser(FloatType.Format.BINARY64, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), true, false, true, true);
        assertThrows(AtomValidationException.class, () -> strict.read(token(".inf")));
    }

    // ── Precision / rounding ─────────────────────────────────────────────────

    @Test
    void float32NarrowsDirectlyAtItsOwnPrecisionNotViaDouble() {
        Number n = FloatParser.FLOAT32.read(token("3.14"));
        assertInstanceOf(Float.class, n);
        assertEquals(3.14f, n);
    }

    @Test
    void float64NaturalTypeIsDouble() {
        assertInstanceOf(Double.class, FloatParser.FLOAT64.read(token("3.14")));
    }

    // ── NaN/Infinity can't be represented as BigDecimal ─────────────────────

    @Test
    void nanCannotNarrowToBigDecimal() {
        assertThrows(IllegalArgumentException.class, () -> FloatParser.FLOAT64.read(token(".nan"), BigDecimal.class));
    }

    @Test
    void ordinaryValueNarrowsToBigDecimalAsTheRoundedValue() {
        // Not the exact "0.1" as written -- the atom's contract is approximate, so even a
        // BigDecimal target reflects the post-rounding float64 value.
        BigDecimal bd = (BigDecimal) FloatParser.FLOAT64.read(token("0.1"), BigDecimal.class);
        assertEquals(new BigDecimal(0.1), bd);
    }

    // ── Subnormal / negative zero flags (unexercised by any built-in, but implemented) ─────────

    @Test
    void subnormalRejectedWhenAllowSubnormalFalse() {
        FloatParser strict = new FloatParser(FloatType.Format.BINARY64, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), true, true, false, true);
        assertThrows(AtomValidationException.class, () -> strict.read(token("4.9e-324")));
    }

    @Test
    void negativeZeroRejectedWhenAllowNegativeZeroFalse() {
        FloatParser strict = new FloatParser(FloatType.Format.BINARY64, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), true, true, true, false);
        assertThrows(AtomValidationException.class, () -> strict.read(token("-0.0")));
    }

    @Test
    void positiveZeroIsFineWhenAllowNegativeZeroFalse() {
        FloatParser strict = new FloatParser(FloatType.Format.BINARY64, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), true, true, true, false);
        assertEquals(0.0, strict.read(token("0.0"), double.class));
    }

    // ── write() ──────────────────────────────────────────────────────────

    @Test
    void writeSpecialValuesUsesGrammarSpellingNotJavaSpelling() {
        // §7.6's special-value spelling (.nan/+.inf/-.inf) is nothing like Double#toString()'s own
        // NaN/Infinity/-Infinity.
        assertEquals(".nan", FloatParser.FLOAT64.write(Double.NaN));
        assertEquals("+.inf", FloatParser.FLOAT64.write(Double.POSITIVE_INFINITY));
        assertEquals("-.inf", FloatParser.FLOAT64.write(Double.NEGATIVE_INFINITY));
    }

    @Test
    void writeFloatFormatsAtFloatPrecisionNotWidenedToDouble() {
        // Formatted from the float itself, not float->double widened first -- widening can
        // introduce extra noise digits for a value like 0.1f that isn't exactly representable.
        float value = (float) FloatParser.FLOAT32.read(token("0.1"), float.class);
        assertEquals(Float.toString(value), FloatParser.FLOAT32.write(value));
        assertEquals("0.1", FloatParser.FLOAT32.write(value));
    }

    @Test
    void writeDoubleRoundTripsThroughRead() {
        double value = (double) FloatParser.FLOAT64.read(token("12.5"), double.class);
        assertEquals("12.5", FloatParser.FLOAT64.write(value));
    }
}
