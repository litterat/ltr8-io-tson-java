package io.ltr8.tson.schema.meta;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RationalTest {

    private static Rational of(long numerator, long denominator) {
        return new Rational(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
    }

    @Test
    void rejectsZeroOrNegativeDenominator() {
        assertThrows(IllegalArgumentException.class, () -> of(1, 0));
        assertThrows(IllegalArgumentException.class, () -> of(1, -3));
    }

    @Test
    void numeratorMayBeNegativeOrZero() {
        assertEquals(BigInteger.valueOf(-2), of(-2, 3).numerator());
        assertEquals(BigInteger.ZERO, of(0, 3).numerator());
    }

    // ── Value equality, not field equality (meta.tn1: "2/4 equals 1/2") ─────────────────────

    @Test
    void equalValuesWithDifferentWrittenFormsAreEqual() {
        assertEquals(of(2, 4), of(1, 2));
        assertEquals(of(1, 2), of(2, 4));
    }

    @Test
    void equalValuesHaveEqualHashCodes() {
        assertEquals(of(2, 4).hashCode(), of(1, 2).hashCode());
    }

    @Test
    void differentValuesAreNotEqual() {
        assertNotEquals(of(1, 2), of(1, 3));
    }

    @Test
    void fieldsAreNotNormalized() {
        // Round-trip fidelity: 2/4 keeps its own numerator/denominator even though it equals 1/2.
        Rational twoQuarters = of(2, 4);
        assertEquals(BigInteger.valueOf(2), twoQuarters.numerator());
        assertEquals(BigInteger.valueOf(4), twoQuarters.denominator());
    }

    @Test
    void zeroValuesAreEqualRegardlessOfDenominator() {
        assertEquals(of(0, 3), of(0, 7));
    }

    // ── compareTo ────────────────────────────────────────────────────────────

    @Test
    void compareToOrdersByValue() {
        assertTrue(of(1, 2).compareTo(of(2, 3)) < 0);
        assertTrue(of(2, 3).compareTo(of(1, 2)) > 0);
        assertEquals(0, of(1, 2).compareTo(of(2, 4)));
    }

    @Test
    void toStringIsNumeratorSlashDenominator() {
        assertEquals("2/4", of(2, 4).toString());
    }
}
