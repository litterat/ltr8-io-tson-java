package io.ltr8.tson.parser.resolver.vocab;

import io.ltr8.tson.parser.ast.TokenForm;
import io.ltr8.tson.parser.ast.TokenValue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ComplexParserTest {

    private static TokenValue token(String text) {
        return new TokenValue(text, TokenForm.UNQUOTED);
    }

    @Test
    void acceptsTwoPartForm() {
        assertEquals(new Complex(new BigDecimal("3"), new BigDecimal("4")),
                ComplexParser.UNCONSTRAINED.read(token("3+4i")));
    }

    @Test
    void acceptsNegativeRealAndImaginaryParts() {
        assertEquals(new Complex(new BigDecimal("-3.5"), new BigDecimal("-2")),
                ComplexParser.UNCONSTRAINED.read(token("-3.5-2j")));
    }

    @Test
    void acceptsImaginaryOnlyForm() {
        // Real part implicitly zero.
        assertEquals(new Complex(BigDecimal.ZERO, new BigDecimal("4")),
                ComplexParser.UNCONSTRAINED.read(token("4i")));
    }

    @Test
    void acceptsPlainIntegerAsRealOnlyComplex() {
        // §5.6: !complex accepts complex/float/integer -- a bare integer is real-only.
        assertEquals(new Complex(new BigDecimal("42"), BigDecimal.ZERO),
                ComplexParser.UNCONSTRAINED.read(token("42")));
    }

    @Test
    void acceptsPlainFloatAsRealOnlyComplex() {
        assertEquals(new Complex(new BigDecimal("3.14"), BigDecimal.ZERO),
                ComplexParser.UNCONSTRAINED.read(token("3.14")));
    }

    @Test
    void basedIntegerFormIsRejected() {
        // §5.6's accepted forms for !complex are complex/float/integer, not based-integer.
        assertThrows(AtomParseException.class, () -> ComplexParser.UNCONSTRAINED.read(token("0xFF")));
    }

    @Test
    void nonNumericTokenIsAParseError() {
        assertThrows(AtomParseException.class, () -> ComplexParser.UNCONSTRAINED.read(token("twelve")));
    }

    @Test
    void missingImagUnitOnTwoMagnitudesIsAParseError() {
        // "3+4" without a trailing i/j doesn't match the complex grammar, and isn't a bare
        // integer/float either.
        assertThrows(AtomParseException.class, () -> ComplexParser.UNCONSTRAINED.read(token("3+4")));
    }

    // ── read(token, target) via AtomType's default ──────────────────────────────────────────

    @Test
    void readWithMatchingTargetReturnsTheValue() {
        Complex expected = new Complex(new BigDecimal("3"), new BigDecimal("4"));
        assertEquals(expected, ComplexParser.UNCONSTRAINED.read(token("3+4i"), Complex.class));
    }

    @Test
    void readWithMismatchedTargetThrows() {
        assertThrows(AtomValidationException.class, () -> ComplexParser.UNCONSTRAINED.read(token("3+4i"), double.class));
    }

    // ── write() ──────────────────────────────────────────────────────────

    @Test
    void writeAlwaysUsesTheFullTwoPartFormWithAnExplicitMiddleSign() {
        assertEquals("3+4i", ComplexParser.UNCONSTRAINED.write(new Complex(new BigDecimal("3"), new BigDecimal("4"))));
        assertEquals("3-4i", ComplexParser.UNCONSTRAINED.write(new Complex(new BigDecimal("3"), new BigDecimal("-4"))));
        assertEquals("-3+4i", ComplexParser.UNCONSTRAINED.write(new Complex(new BigDecimal("-3"), new BigDecimal("4"))));
    }

    @Test
    void writeUsesPlainStringNotScientificNotation() {
        Complex value = new Complex(new BigDecimal("0.0001"), new BigDecimal("2000000"));
        assertEquals("0.0001+2000000i", ComplexParser.UNCONSTRAINED.write(value));
    }
}
