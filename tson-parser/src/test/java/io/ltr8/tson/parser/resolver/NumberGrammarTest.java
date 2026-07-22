package io.ltr8.tson.parser.resolver;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NumberGrammarTest {

    private static Optional<NumberForm> parse(String text) {
        return NumberGrammar.tryParse(text);
    }

    private static void assertNoMatch(String text) {
        assertTrue(parse(text).isEmpty(), () -> "expected '" + text + "' not to match the number grammar");
    }

    // ── Integers ─────────────────────────────────────────────────────────

    @Test
    void plainInteger() {
        NumberForm.IntegerForm f = assertInstanceOf(NumberForm.IntegerForm.class, parse("42").orElseThrow());
        assertTrue(f.sign().isEmpty());
        assertEquals("42", f.digits());
    }

    @Test
    void singleZeroIsValid() {
        NumberForm.IntegerForm f = assertInstanceOf(NumberForm.IntegerForm.class, parse("0").orElseThrow());
        assertEquals("0", f.digits());
    }

    @Test
    void signedIntegers() {
        NumberForm.IntegerForm plus = assertInstanceOf(NumberForm.IntegerForm.class, parse("+42").orElseThrow());
        assertEquals(NumberForm.Sign.PLUS, plus.sign().orElseThrow());
        NumberForm.IntegerForm minus = assertInstanceOf(NumberForm.IntegerForm.class, parse("-42").orElseThrow());
        assertEquals(NumberForm.Sign.MINUS, minus.sign().orElseThrow());
    }

    @Test
    void underscoreSeparatedInteger() {
        NumberForm.IntegerForm f = assertInstanceOf(NumberForm.IntegerForm.class, parse("1_000_000").orElseThrow());
        assertEquals("1_000_000", f.digits());
    }

    @Test
    void leadingZeroIsRejected() {
        assertNoMatch("007");
        assertNoMatch("00");
        assertNoMatch("01");
    }

    @Test
    void doubleUnderscoreIsRejected() {
        assertNoMatch("1__000");
    }

    @Test
    void trailingUnderscoreIsRejected() {
        assertNoMatch("100_");
    }

    // ── Based integers ───────────────────────────────────────────────────

    @Test
    void hexInteger() {
        NumberForm.BasedIntegerForm f = assertInstanceOf(NumberForm.BasedIntegerForm.class, parse("0xFF").orElseThrow());
        assertEquals(NumberForm.BasedIntegerForm.Radix.HEX, f.radix());
        assertEquals("FF", f.digits());
    }

    @Test
    void hexDigitsAllowMixedCase() {
        NumberForm.BasedIntegerForm f = assertInstanceOf(NumberForm.BasedIntegerForm.class, parse("0xFf1a").orElseThrow());
        assertEquals("Ff1a", f.digits());
    }

    @Test
    void hexPrefixMustBeLowercase() {
        assertNoMatch("0XFF");
    }

    @Test
    void octalInteger() {
        NumberForm.BasedIntegerForm f = assertInstanceOf(NumberForm.BasedIntegerForm.class, parse("0o755").orElseThrow());
        assertEquals(NumberForm.BasedIntegerForm.Radix.OCTAL, f.radix());
        assertEquals("755", f.digits());
    }

    @Test
    void binaryInteger() {
        NumberForm.BasedIntegerForm f = assertInstanceOf(NumberForm.BasedIntegerForm.class, parse("0b1010").orElseThrow());
        assertEquals(NumberForm.BasedIntegerForm.Radix.BINARY, f.radix());
        assertEquals("1010", f.digits());
    }

    @Test
    void signedBasedInteger() {
        NumberForm.BasedIntegerForm f = assertInstanceOf(NumberForm.BasedIntegerForm.class, parse("-0x1A").orElseThrow());
        assertEquals(NumberForm.Sign.MINUS, f.sign().orElseThrow());
    }

    @Test
    void blockchainAddressShapedHexMatches() {
        // Spec §4.3's own example: hex-shaped identifier data resolves as a number if unquoted.
        assertTrue(parse("0x71C7656EC7ab88b098defB751B7401B5f6d8976F").isPresent());
    }

    @Test
    void basedIntegerRequiresAtLeastOneDigit() {
        assertNoMatch("0x");
        assertNoMatch("0o");
        assertNoMatch("0b");
    }

    // ── Floats ───────────────────────────────────────────────────────────

    @Test
    void simpleFloat() {
        NumberForm.FloatForm f = assertInstanceOf(NumberForm.FloatForm.class, parse("1.5").orElseThrow());
        assertEquals("1", f.integerPart().orElseThrow());
        assertEquals("5", f.fractionDigits().orElseThrow());
        assertTrue(f.exponent().isEmpty());
    }

    @Test
    void leadingDotFloat() {
        NumberForm.FloatForm f = assertInstanceOf(NumberForm.FloatForm.class, parse(".5").orElseThrow());
        assertTrue(f.integerPart().isEmpty());
        assertEquals("5", f.fractionDigits().orElseThrow());
    }

    @Test
    void floatWithExponent() {
        NumberForm.FloatForm f = assertInstanceOf(NumberForm.FloatForm.class, parse("6.02e23").orElseThrow());
        assertEquals("6", f.integerPart().orElseThrow());
        assertEquals("02", f.fractionDigits().orElseThrow());
        NumberForm.ExponentPart exp = f.exponent().orElseThrow();
        assertTrue(exp.sign().isEmpty());
        assertEquals("23", exp.digits());
    }

    @Test
    void negativeExponentWithSign() {
        NumberForm.FloatForm f = assertInstanceOf(NumberForm.FloatForm.class, parse("-2e-3").orElseThrow());
        assertEquals(NumberForm.Sign.MINUS, f.sign().orElseThrow());
        assertTrue(f.fractionDigits().isEmpty());
        NumberForm.ExponentPart exp = f.exponent().orElseThrow();
        assertEquals(NumberForm.Sign.MINUS, exp.sign().orElseThrow());
        assertEquals("3", exp.digits());
    }

    @Test
    void exponentUppercaseEAllowed() {
        assertTrue(parse("1E10").isPresent());
    }

    @Test
    void signedZeroFloatsPreserveSign() {
        NumberForm.FloatForm plus = assertInstanceOf(NumberForm.FloatForm.class, parse("+0.0").orElseThrow());
        assertEquals(NumberForm.Sign.PLUS, plus.sign().orElseThrow());
        NumberForm.FloatForm minus = assertInstanceOf(NumberForm.FloatForm.class, parse("-0.0").orElseThrow());
        assertEquals(NumberForm.Sign.MINUS, minus.sign().orElseThrow());
    }

    @Test
    void integerPartWithExponentNoDot() {
        NumberForm.FloatForm f = assertInstanceOf(NumberForm.FloatForm.class, parse("1e10").orElseThrow());
        assertEquals("1", f.integerPart().orElseThrow());
        assertTrue(f.fractionDigits().isEmpty());
        assertEquals("10", f.exponent().orElseThrow().digits());
    }

    @Test
    void trailingDotWithNoDigitsIsRejected() {
        // Spec §4.3: "Digits MUST follow a decimal point... 5. is not a number."
        assertNoMatch("5.");
    }

    @Test
    void secondDotIsRejected() {
        // Spec §4.4's own example.
        assertNoMatch("1.2.3");
    }

    // ── Special values ───────────────────────────────────────────────────

    @Test
    void infinityForms() {
        assertEquals(NumberForm.SpecialValueForm.Kind.INFINITY,
                ((NumberForm.SpecialValueForm) parse(".inf").orElseThrow()).kind());
        assertEquals(NumberForm.SpecialValueForm.Kind.INFINITY,
                ((NumberForm.SpecialValueForm) parse(".infinity").orElseThrow()).kind());
    }

    @Test
    void signedInfinity() {
        NumberForm.SpecialValueForm f = assertInstanceOf(NumberForm.SpecialValueForm.class, parse("-.inf").orElseThrow());
        assertEquals(NumberForm.Sign.MINUS, f.sign().orElseThrow());
        NumberForm.SpecialValueForm f2 = assertInstanceOf(NumberForm.SpecialValueForm.class, parse("+.infinity").orElseThrow());
        assertEquals(NumberForm.Sign.PLUS, f2.sign().orElseThrow());
    }

    @Test
    void nan() {
        NumberForm.SpecialValueForm f = assertInstanceOf(NumberForm.SpecialValueForm.class, parse(".nan").orElseThrow());
        assertEquals(NumberForm.SpecialValueForm.Kind.NAN, f.kind());
        assertTrue(f.sign().isEmpty());
    }

    @Test
    void nanIsNeverSigned() {
        assertNoMatch("+.nan");
        assertNoMatch("-.nan");
    }

    @Test
    void specialValueNamesAreLowercaseOnly() {
        assertNoMatch(".Inf");
        assertNoMatch(".NAN");
        assertNoMatch(".Infinity");
    }

    // ── Non-numeric fall-through ─────────────────────────────────────────

    @Test
    void complexFormDoesNotMatch() {
        // Spec §4.3: complex tokens resolve as strings under base resolution.
        assertNoMatch("3+4i");
    }

    @Test
    void plainWordsDoNotMatch() {
        assertNoMatch("GOLD");
        assertNoMatch("A-100");
    }

    @Test
    void emptyStringDoesNotMatch() {
        assertNoMatch("");
    }

    @Test
    void dateLikeTokenDoesNotMatch() {
        // 2025-03-13 is not a number per base resolution (it's a string, or a typed !date atom).
        assertNoMatch("2025-03-13");
    }

    @Test
    void versionLikeTokenDoesNotMatch() {
        assertNoMatch("v1.2.3");
    }

    // ── Equivalent representations all parse (equivalence itself is a binding-layer concern) ──

    @Test
    void equivalentRepresentationsAllRecognized() {
        assertTrue(parse("255").isPresent());
        assertTrue(parse("0xFF").isPresent());
        assertTrue(parse("6.02e23").isPresent());
        assertTrue(parse("602e21").isPresent());
        assertTrue(parse(".5").isPresent());
        assertTrue(parse("0.5").isPresent());
        assertTrue(parse("1_000").isPresent());
        assertTrue(parse("1000").isPresent());
        assertTrue(parse("+42").isPresent());
        assertTrue(parse("42").isPresent());
    }

    @Test
    void disjointFormsNeverBothMatch() {
        // Sanity check on the "pairwise disjoint" claim (§7.6) for a representative sample.
        for (String s : new String[]{"42", "0xFF", "1.5", ".inf", ".nan", "1e10", "-0.0"}) {
            assertFalse(parse(s).isEmpty(), s);
        }
    }

    // ── Extended forms (§7.6, only reachable through §5.6's atoms, not tryParse) ────────────────

    @Test
    void hexFloatNotRecognizedByTryParse() {
        assertNoMatch("0x1.8p3");
    }

    @Test
    void rationalNotRecognizedByTryParse() {
        assertNoMatch("2/3");
    }

    @Test
    void complexNotRecognizedByTryParse() {
        assertNoMatch("3+4i");
    }

    @Test
    void hexFloatRecognized() {
        assertTrue(NumberGrammar.isHexFloat("0x1.8p3"));
        assertTrue(NumberGrammar.isHexFloat("0x.8p1"));
        assertTrue(NumberGrammar.isHexFloat("-0x1p-1074"));
        assertFalse(NumberGrammar.isHexFloat("0xFF")); // based-integer, no 'p' exponent -- not a hex-float
        assertFalse(NumberGrammar.isHexFloat("1.5")); // plain decimal float, no "0x"
    }

    @Test
    void rationalRecognized() {
        RationalForm f = NumberGrammar.tryRational("2/3").orElseThrow();
        assertEquals(Optional.empty(), f.sign());
        assertEquals("2", f.numerator());
        assertEquals("3", f.denominator());
    }

    @Test
    void rationalSignAppliesToNumeratorOnly() {
        RationalForm f = NumberGrammar.tryRational("-2/3").orElseThrow();
        assertEquals(Optional.of(NumberForm.Sign.MINUS), f.sign());
        assertEquals("2", f.numerator());
        assertEquals("3", f.denominator());
    }

    @Test
    void rationalNumeratorMayBeZero() {
        RationalForm f = NumberGrammar.tryRational("0/5").orElseThrow();
        assertEquals("0", f.numerator());
    }

    @Test
    void rationalDenominatorCannotBeZeroOrLeadingZero() {
        // denominator = nonzero-digit *( ["_"] DIGIT ) -- no "0" alternative, unlike the numerator.
        assertTrue(NumberGrammar.tryRational("1/0").isEmpty());
        assertTrue(NumberGrammar.tryRational("1/05").isEmpty());
    }

    @Test
    void rationalDenominatorCannotBeSigned() {
        assertTrue(NumberGrammar.tryRational("1/-3").isEmpty());
    }

    @Test
    void rationalUnderscoreSeparatorsInDenominator() {
        RationalForm f = NumberGrammar.tryRational("1/1_000").orElseThrow();
        assertEquals("1_000", f.denominator());
    }

    @Test
    void complexTwoPartForm() {
        ComplexForm f = NumberGrammar.tryComplex("3+4i").orElseThrow();
        assertEquals(Optional.empty(), f.realSign());
        assertEquals(Optional.of("3"), f.realMagnitude());
        assertEquals(Optional.of(NumberForm.Sign.PLUS), f.imaginarySign());
        assertEquals("4", f.imaginaryMagnitude());
    }

    @Test
    void complexTwoPartFormWithNegativeRealAndImaginary() {
        ComplexForm f = NumberGrammar.tryComplex("-3.5-2e1j").orElseThrow();
        assertEquals(Optional.of(NumberForm.Sign.MINUS), f.realSign());
        assertEquals(Optional.of("3.5"), f.realMagnitude());
        assertEquals(Optional.of(NumberForm.Sign.MINUS), f.imaginarySign());
        assertEquals("2e1", f.imaginaryMagnitude());
    }

    @Test
    void complexTwoPartFormRequiresAnExplicitMiddleSign() {
        // "3 4i" or "3,4i" -- no separator sign between the parts -- doesn't match at all.
        assertTrue(NumberGrammar.tryComplex("3 4i").isEmpty());
    }

    @Test
    void complexImaginaryOnlyForm() {
        ComplexForm f = NumberGrammar.tryComplex("4i").orElseThrow();
        assertEquals(Optional.empty(), f.realSign());
        assertEquals(Optional.empty(), f.realMagnitude());
        assertEquals(Optional.empty(), f.imaginarySign());
        assertEquals("4", f.imaginaryMagnitude());
    }

    @Test
    void complexImaginaryOnlyFormNegative() {
        ComplexForm f = NumberGrammar.tryComplex("-2.5j").orElseThrow();
        assertEquals(Optional.empty(), f.realSign());
        assertEquals(Optional.empty(), f.realMagnitude());
        assertEquals(Optional.of(NumberForm.Sign.MINUS), f.imaginarySign());
        assertEquals("2.5", f.imaginaryMagnitude());
    }

    @Test
    void complexBareMagnitudeWithoutImagUnitDoesNotMatch() {
        // "3+4" (no trailing i/j) is not a complex form at all.
        assertTrue(NumberGrammar.tryComplex("3+4").isEmpty());
    }

    @Test
    void complexMagnitudeAcceptsBareNaturalNumberWithNoDotOrExponent() {
        // magnitude, unlike base float, allows a bare natural number (no dot, no exponent).
        ComplexForm f = NumberGrammar.tryComplex("3+4i").orElseThrow();
        assertEquals(Optional.of("3"), f.realMagnitude());
        assertEquals("4", f.imaginaryMagnitude());
    }
}
