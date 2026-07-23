package io.ltr8.tson.parser.resolver.vocab;

import io.ltr8.tson.parser.ast.TokenForm;
import io.ltr8.tson.parser.ast.TokenValue;
import io.ltr8.tson.schema.meta.Rational;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RationalParserTest {

    private static TokenValue token(String text) {
        return new TokenValue(text, TokenForm.UNQUOTED);
    }

    private static Rational of(long numerator, long denominator) {
        return new Rational(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
    }

    @Test
    void acceptsPlainRational() {
        assertEquals(of(2, 3), RationalParser.UNCONSTRAINED.read(token("2/3")));
    }

    @Test
    void signAppliesToTheWholeValueViaTheNumerator() {
        assertEquals(of(-2, 3), RationalParser.UNCONSTRAINED.read(token("-2/3")));
    }

    @Test
    void numeratorMayBeZero() {
        assertEquals(of(0, 5), RationalParser.UNCONSTRAINED.read(token("0/5")));
    }

    @Test
    void nonRationalTokenIsAParseError() {
        assertThrows(AtomParseException.class, () -> RationalParser.UNCONSTRAINED.read(token("twelve")));
    }

    @Test
    void plainIntegerWithoutSlashIsAParseError() {
        // §5.6: !rational accepts only the rational grammar form, not bare integer/float.
        assertThrows(AtomParseException.class, () -> RationalParser.UNCONSTRAINED.read(token("2")));
    }

    @Test
    void zeroDenominatorIsAParseError() {
        // Rejected by the grammar itself (denominator = nonzero-digit ...), before Rational's own
        // constructor validation ever runs.
        assertThrows(AtomParseException.class, () -> RationalParser.UNCONSTRAINED.read(token("1/0")));
    }

    @Test
    void underscoreSeparatedDenominator() {
        assertEquals(of(1, 1000), RationalParser.UNCONSTRAINED.read(token("1/1_000")));
    }

    // ── read(token) returns natural type Rational (single legitimate representation) ───────

    @Test
    void readReturnsRationalDirectly() {
        assertEquals(of(2, 3), RationalParser.UNCONSTRAINED.read(token("2/3")));
    }

    // ── read(token, target) via AtomType's default (no override needed) ────────────────────

    @Test
    void readWithMatchingTargetReturnsTheValue() {
        assertEquals(of(2, 3), RationalParser.UNCONSTRAINED.read(token("2/3"), Rational.class));
    }

    @Test
    void readWithMismatchedTargetThrows() {
        // No narrowing to double/BigDecimal is implemented -- an application wanting that binds
        // through its own DataBridge instead (see Rational's Javadoc).
        assertThrows(AtomValidationException.class, () -> RationalParser.UNCONSTRAINED.read(token("2/3"), double.class));
    }

    // ── Constraint vocabulary (unexercised by the built-in instance, but implemented) ──────

    @Test
    void minRejectsBelowBound() {
        RationalParser type = new RationalParser(Optional.of(of(1, 2)), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        assertEquals(of(1, 2), type.read(token("1/2")));
        assertThrows(AtomValidationException.class, () -> type.read(token("1/3")));
    }

    @Test
    void maxRejectsAboveBound() {
        RationalParser type = new RationalParser(Optional.empty(), Optional.empty(), Optional.of(of(1, 2)), Optional.empty(), Optional.empty());
        assertEquals(of(1, 3), type.read(token("1/3")));
        assertThrows(AtomValidationException.class, () -> type.read(token("2/3")));
    }

    @Test
    void multipleOfRejectsNonMultiples() {
        RationalParser type = new RationalParser(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(of(1, 6)));
        assertEquals(of(1, 3), type.read(token("2/6"))); // 2/6 = 1/3 = 2 * (1/6)
        assertThrows(AtomValidationException.class, () -> type.read(token("1/4")));
    }

    @Test
    void writeGivesNumeratorSlashDenominator() {
        // Not normalized: "2/4" round-trips as "2/4", per Rational's own equals-by-value/
        // written-as-preserved distinction (see Rational's Javadoc).
        assertEquals("2/4", RationalParser.UNCONSTRAINED.write(RationalParser.UNCONSTRAINED.read(token("2/4"))));
        assertEquals("-1/3", RationalParser.UNCONSTRAINED.write(RationalParser.UNCONSTRAINED.read(token("-1/3"))));
    }
}
