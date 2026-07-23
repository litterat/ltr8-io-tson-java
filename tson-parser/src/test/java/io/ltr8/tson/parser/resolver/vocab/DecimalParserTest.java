package io.ltr8.tson.parser.resolver.vocab;

import io.ltr8.tson.parser.ast.TokenForm;
import io.ltr8.tson.parser.ast.TokenValue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DecimalParserTest {

    private static TokenValue token(String text) {
        return new TokenValue(text, TokenForm.UNQUOTED);
    }

    private static DecimalParser decimalType(Optional<BigDecimal> min, Optional<BigDecimal> multipleOf,
            Optional<Integer> totalDigits, Optional<Integer> fractionDigits) {
        return new DecimalParser(min, Optional.empty(), Optional.empty(), Optional.empty(),
                multipleOf, totalDigits, fractionDigits);
    }

    // ── §7.6 form acceptance (§5.6: "!number accepts integer/float, not the special values") ──

    @Test
    void acceptsPlainIntegers() {
        assertEquals(new BigDecimal("42"), DecimalParser.UNCONSTRAINED.read(token("42")));
    }

    @Test
    void acceptsFloats() {
        assertEquals(new BigDecimal("199.90"), DecimalParser.UNCONSTRAINED.read(token("199.90")));
    }

    @Test
    void preservesExactlyAsWritten() {
        // §5.6: exact tier, "preserved as written" -- 199.90 keeps its trailing zero, unlike a
        // double round-trip which would normalize it away.
        assertEquals("199.90", DecimalParser.UNCONSTRAINED.read(token("199.90")).toString());
    }

    @Test
    void basedIntegerFormIsRejected() {
        // §5.6: number's accepted forms are integer/float only, not based-integer.
        assertThrows(AtomParseException.class, () -> DecimalParser.UNCONSTRAINED.read(token("0xFF")));
    }

    @Test
    void specialValuesAreRejected() {
        // §5.6: "!number, being exact, does not accept the special values."
        assertThrows(AtomParseException.class, () -> DecimalParser.UNCONSTRAINED.read(token(".inf")));
        assertThrows(AtomParseException.class, () -> DecimalParser.UNCONSTRAINED.read(token(".nan")));
    }

    @Test
    void nonNumericTokenIsAParseError() {
        assertThrows(AtomParseException.class, () -> DecimalParser.UNCONSTRAINED.read(token("twelve")));
    }

    // ── Constraint vocabulary ────────────────────────────────────────────────

    @Test
    void minRejectsBelowBound() {
        DecimalParser type = decimalType(Optional.of(new BigDecimal("0")), Optional.empty(), Optional.empty(), Optional.empty());
        assertEquals(new BigDecimal("0"), type.read(token("0")));
        assertThrows(AtomValidationException.class, () -> type.read(token("-0.01")));
    }

    @Test
    void multipleOfRejectsNonMultiples() {
        DecimalParser type = decimalType(Optional.empty(), Optional.of(new BigDecimal("0.05")), Optional.empty(), Optional.empty());
        assertEquals(new BigDecimal("0.10"), type.read(token("0.10")));
        assertThrows(AtomValidationException.class, () -> type.read(token("0.11")));
    }

    @Test
    void totalDigitsRejectsTooManySignificantDigits() {
        DecimalParser type = decimalType(Optional.empty(), Optional.empty(), Optional.of(3), Optional.empty());
        assertEquals(new BigDecimal("123"), type.read(token("123")));
        assertThrows(AtomValidationException.class, () -> type.read(token("1234")));
    }

    @Test
    void fractionDigitsRejectsTooManyDecimalPlaces() {
        DecimalParser type = decimalType(Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(2));
        assertEquals(new BigDecimal("1.23"), type.read(token("1.23")));
        assertThrows(AtomValidationException.class, () -> type.read(token("1.234")));
    }

    // ── read(token, target) ──────────────────────────────────────────────────

    @Test
    void readWithTargetNarrowsToDoubleOrFloat() {
        assertEquals(199.90, DecimalParser.UNCONSTRAINED.read(token("199.90"), double.class));
        assertEquals(199.90f, DecimalParser.UNCONSTRAINED.read(token("199.90"), float.class));
    }

    @Test
    void writeRoundTripsThroughRead() {
        BigDecimal value = DecimalParser.UNCONSTRAINED.read(token("199.90"));
        assertEquals("199.90", DecimalParser.UNCONSTRAINED.write(value));
    }
}
