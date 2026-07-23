package io.ltr8.tson.parser.resolver.vocab;

import io.ltr8.tson.parser.ast.TokenForm;
import io.ltr8.tson.parser.ast.TokenValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IntegerTypeTest {

    private static TokenValue token(String text) {
        return new TokenValue(text, TokenForm.UNQUOTED);
    }

    private static BigInteger big(IntegerType type, String text) {
        return (BigInteger) type.read(token(text), BigInteger.class);
    }

    // ── §7.6 form acceptance (§5.6: "only integer/based-integer forms") ────

    @Test
    void acceptsPlainDecimalIntegers() {
        IntegerType int32 = new IntegerType(new IntegerSize(32, true));
        assertEquals(BigInteger.valueOf(42), big(int32, "42"));
        assertEquals(BigInteger.valueOf(-42), big(int32, "-42"));
    }

    @Test
    void acceptsBasedIntegersUniformly() {
        // §5.6: "the integer atoms accept based and signed forms uniformly".
        IntegerType uint32 = new IntegerType(new IntegerSize(32, false));
        assertEquals(BigInteger.valueOf(0xFF00_0000L), big(uint32, "0xFF00_0000"));
    }

    @Test
    void digitSeparatorsAreStripped() {
        IntegerType int64 = new IntegerType(new IntegerSize(64, true));
        assertEquals(BigInteger.valueOf(1_000_000), big(int64, "1_000_000"));
    }

    @Test
    void nonNumericTokenIsAParseError() {
        // §5.6: "twelve under !int32 is a parse error".
        IntegerType int32 = new IntegerType(new IntegerSize(32, true));
        assertThrows(AtomParseException.class, () -> int32.read(token("twelve")));
    }

    @Test
    void floatTokenIsAParseErrorUnderAnIntegerAtom() {
        // §5.6's integer atoms accept only integer/based-integer, unlike float32/float64.
        IntegerType int32 = new IntegerType(new IntegerSize(32, true));
        assertThrows(AtomParseException.class, () -> int32.read(token("3.14")));
    }

    @Test
    void specialValueTokenIsAParseErrorUnderAnIntegerAtom() {
        IntegerType int32 = new IntegerType(new IntegerSize(32, true));
        assertThrows(AtomParseException.class, () -> int32.read(token(".inf")));
    }

    // ── fixed-width range validation ────────────────────────────────────────

    @Test
    void outOfRangeValueIsAValidationErrorNotAParseError() {
        // §5.6: "9999999999 under !int32 parses as an integer and then fails validation".
        IntegerType int32 = new IntegerType(new IntegerSize(32, true));
        assertThrows(AtomValidationException.class, () -> int32.read(token("9999999999")));
    }

    @Test
    void negativeValueUnderUnsignedParsesThenFailsValidation() {
        // §5.6: "the range constraint, not the lexer, enforces unsignedness".
        IntegerType uint32 = new IntegerType(new IntegerSize(32, false));
        assertThrows(AtomValidationException.class, () -> uint32.read(token("-10")));
    }

    @Test
    void int32BoundariesAreInclusive() {
        IntegerType int32 = new IntegerType(new IntegerSize(32, true));
        assertEquals(BigInteger.valueOf(Integer.MIN_VALUE), big(int32, "-2147483648"));
        assertEquals(BigInteger.valueOf(Integer.MAX_VALUE), big(int32, "2147483647"));
        assertThrows(AtomValidationException.class, () -> int32.read(token("-2147483649")));
        assertThrows(AtomValidationException.class, () -> int32.read(token("2147483648")));
    }

    @Test
    void uint32BoundariesAreInclusive() {
        IntegerType uint32 = new IntegerType(new IntegerSize(32, false));
        assertEquals(BigInteger.ZERO, big(uint32, "0"));
        assertEquals(BigInteger.valueOf(4_294_967_295L), big(uint32, "4294967295"));
        assertThrows(AtomValidationException.class, () -> uint32.read(token("4294967296")));
    }

    @Test
    void wideWidthsExceedingLongStillValidate() {
        IntegerType int128 = new IntegerType(new IntegerSize(128, true));
        BigInteger max = BigInteger.TWO.pow(127).subtract(BigInteger.ONE);
        assertEquals(max, big(int128, max.toString()));
        assertThrows(AtomValidationException.class, () -> int128.read(token(max.add(BigInteger.ONE).toString())));
    }

    // ── bound-only refinements (no size) ────────────────────────────────────

    @Test
    void positiveIntegerRejectsZeroAndNegative() {
        IntegerType positiveInteger = IntegerType.ofMin(BigInteger.ONE);
        assertEquals(BigInteger.ONE, big(positiveInteger, "1"));
        assertThrows(AtomValidationException.class, () -> positiveInteger.read(token("0")));
        assertThrows(AtomValidationException.class, () -> positiveInteger.read(token("-1")));
    }

    @Test
    void nonNegativeIntegerAcceptsZero() {
        IntegerType nonNegative = IntegerType.ofMin(BigInteger.ZERO);
        assertEquals(BigInteger.ZERO, big(nonNegative, "0"));
        assertThrows(AtomValidationException.class, () -> nonNegative.read(token("-1")));
    }

    @Test
    void negativeIntegerRejectsZeroAndPositive() {
        IntegerType negativeInteger = IntegerType.ofMax(BigInteger.valueOf(-1));
        assertEquals(BigInteger.valueOf(-1), big(negativeInteger, "-1"));
        assertThrows(AtomValidationException.class, () -> negativeInteger.read(token("0")));
    }

    @Test
    void nonPositiveIntegerAcceptsZero() {
        IntegerType nonPositive = IntegerType.ofMax(BigInteger.ZERO);
        assertEquals(BigInteger.ZERO, big(nonPositive, "0"));
        assertThrows(AtomValidationException.class, () -> nonPositive.read(token("1")));
    }

    // ── unconstrained ────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"0", "-99999999999999999999999999999", "99999999999999999999999999999"})
    void unconstrainedAcceptsAnyMagnitude(String text) {
        assertEquals(new BigInteger(text), big(IntegerType.UNCONSTRAINED, text));
    }

    // ── read(token) narrows to the atom's own natural host type ────────────

    @Test
    void signedFixedWidthsNarrowToTheirOwnPrimitive() {
        assertEquals((byte) 42, new IntegerType(new IntegerSize(8, true)).read(token("42")));
        assertEquals((short) 42, new IntegerType(new IntegerSize(16, true)).read(token("42")));
        assertEquals(42, new IntegerType(new IntegerSize(32, true)).read(token("42")));
        assertEquals(42L, new IntegerType(new IntegerSize(64, true)).read(token("42")));
    }

    @Test
    void unsignedFixedWidthsNarrowToTheNextWiderSignedPrimitive() {
        // Java has no unsigned primitives -- unsigned n-bit needs signed (n+1)-bit's range.
        assertInstanceOf(Short.class, new IntegerType(new IntegerSize(8, false)).read(token("200")));
        assertInstanceOf(Integer.class, new IntegerType(new IntegerSize(16, false)).read(token("50000")));
        assertInstanceOf(Long.class, new IntegerType(new IntegerSize(32, false)).read(token("4000000000")));
    }

    @Test
    void wideAndUnconstrainedInstancesReadAsBigInteger() {
        assertInstanceOf(BigInteger.class, new IntegerType(new IntegerSize(128, true)).read(token("42")));
        assertInstanceOf(BigInteger.class, IntegerType.UNCONSTRAINED.read(token("42")));
        assertInstanceOf(BigInteger.class, IntegerType.ofMin(BigInteger.ONE).read(token("42")));
    }

    // ── read(token, target) narrows directly to a caller-supplied target ───

    @Test
    void readWithTargetSkipsTheNaturalWidthRoundTrip() {
        // !uint8 42 bound directly to an int-typed field: one call, still validated against
        // uint8's own 0..255 contract even though its natural host type is Short.
        IntegerType uint8 = new IntegerType(new IntegerSize(8, false));
        assertEquals(42, uint8.read(token("42"), int.class));
        assertThrows(AtomValidationException.class, () -> uint8.read(token("256"), int.class));
    }

    @Test
    void readWithTargetThrowsArithmeticExceptionWhenNarrowerThanTheAtomsOwnRange() {
        // int32's own contract permits this value; a caller requesting byte can't hold it.
        IntegerType int32 = new IntegerType(new IntegerSize(32, true));
        assertThrows(ArithmeticException.class, () -> int32.read(token("200"), byte.class));
    }

    @Test
    void readWithTargetAgreesAcrossEveryRepresentation() {
        IntegerType int32 = new IntegerType(new IntegerSize(32, true));
        assertEquals((byte) 5, int32.read(token("5"), byte.class));
        assertEquals((short) 5, int32.read(token("5"), short.class));
        assertEquals(5, int32.read(token("5"), int.class));
        assertEquals(5L, int32.read(token("5"), long.class));
        assertEquals(BigInteger.valueOf(5), int32.read(token("5"), BigInteger.class));
    }

    @Test
    void readWithTargetAcceptsBoxedAndPrimitiveClassesIdentically() {
        IntegerType int32 = new IntegerType(new IntegerSize(32, true));
        assertEquals(5, int32.read(token("5"), int.class));
        assertEquals(5, int32.read(token("5"), Integer.class));
    }

    // ── write() ──────────────────────────────────────────────────────────

    @Test
    void writeRoundTripsThroughRead() {
        IntegerType int32 = new IntegerType(new IntegerSize(32, true));
        assertEquals("42", int32.write(int32.read(token("42"))));
        assertEquals("-42", int32.write(int32.read(token("-42"))));
    }

    @Test
    void writeIgnoresOriginalBasedForm() {
        // §5.6's own equivalence rule: 0xFF and 255 bind to the same value, and write() has no way
        // (or need) to recover which form the token was originally written in.
        IntegerType uint32 = new IntegerType(new IntegerSize(32, false));
        assertEquals("255", uint32.write(uint32.read(token("0xFF"))));
    }
}
