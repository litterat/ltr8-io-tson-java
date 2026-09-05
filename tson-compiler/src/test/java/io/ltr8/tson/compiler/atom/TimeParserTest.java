package io.ltr8.tson.compiler.atom;

import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.schema.meta.TimeType;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.OffsetTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeParserTest {

    private static TokenValue token(String text) {
        return new TokenValue(text, TokenForm.UNQUOTED);
    }

    @Test
    void acceptsUtcTime() {
        assertEquals(OffsetTime.parse("10:15:30Z"), TimeParser.UNCONSTRAINED.read(token("10:15:30Z")));
    }

    @Test
    void acceptsLowercaseZ() {
        // RFC 3339 explicitly allows lowercase 'z' -- OffsetTime.parse already gets this right
        // natively, confirmed empirically.
        assertEquals(OffsetTime.parse("10:15:30Z"), TimeParser.UNCONSTRAINED.read(token("10:15:30z")));
    }

    @Test
    void acceptsNumericOffset() {
        assertEquals(OffsetTime.parse("10:15:30+05:30"), TimeParser.UNCONSTRAINED.read(token("10:15:30+05:30")));
    }

    @Test
    void acceptsFractionalSeconds() {
        assertEquals(OffsetTime.parse("10:15:30.123Z"), TimeParser.UNCONSTRAINED.read(token("10:15:30.123Z")));
    }

    @Test
    void missingOffsetIsRejected() {
        assertThrows(AtomParseException.class, () -> TimeParser.UNCONSTRAINED.read(token("10:15:30")));
    }

    @Test
    void invalidHourIsRejected() {
        assertThrows(AtomParseException.class, () -> TimeParser.UNCONSTRAINED.read(token("24:00:00Z")));
    }

    @Test
    void leapSecondIsRejected() {
        // RFC 3339's grammar permits time-second up to 60 for leap-second accommodation, but
        // java.time has no leap-second concept at all -- documented limitation, not fixed here.
        assertThrows(AtomParseException.class, () -> TimeParser.UNCONSTRAINED.read(token("23:59:60Z")));
    }

    @Test
    void nonTimeTokenIsAParseError() {
        assertThrows(AtomParseException.class, () -> TimeParser.UNCONSTRAINED.read(token("not-a-time")));
    }

    @Test
    void minRejectsEarlierTime() {
        TimeParser type = new TimeParser(Optional.of(OffsetTime.parse("09:00:00Z")), Optional.empty());
        assertEquals(OffsetTime.parse("09:00:00Z"), type.read(token("09:00:00Z")));
        assertThrows(AtomValidationException.class, () -> type.read(token("08:59:59Z")));
    }

    @Test
    void maxRejectsLaterTime() {
        TimeParser type = new TimeParser(Optional.empty(), Optional.of(OffsetTime.parse("09:00:00Z")));
        assertEquals(OffsetTime.parse("09:00:00Z"), type.read(token("09:00:00Z")));
        assertThrows(AtomValidationException.class, () -> type.read(token("09:00:01Z")));
    }

    @Test
    void writeRoundTripsThroughRead() {
        assertEquals("10:15:30+01:00",
                TimeParser.UNCONSTRAINED.write(TimeParser.UNCONSTRAINED.read(token("10:15:30+01:00"))));
    }

    /**
     * <b>{@code precision} constrains the value, not the spelling</b> (§5.5): {@code precision: N} admits an
     * instant that is a whole number of 10⁻ᴺ seconds. meta.tn's own {@code @doc} gives the example this
     * turns on -- "a text encoding may spell an admitted value with trailing zeros ({@code 12:00:00.500}
     * under {@code precision: 1})". The atom is exact either way: an instant genuinely off the grid is
     * refused rather than rounded onto it.
     */
    @Test
    void precisionConstrainsTheValueNotTheSpelling() {
        TimeParser type = new TimeParser(new TimeType(Optional.empty(), Optional.empty(),
                Optional.of(BigInteger.valueOf(3))));
        assertEquals(OffsetTime.parse("10:15:30Z"), type.read(token("10:15:30Z")));
        assertEquals(OffsetTime.parse("10:15:30.123Z"), type.read(token("10:15:30.123Z")));
        // Six written digits and a millisecond value: the grid is what is tested, not the digit count.
        assertEquals(OffsetTime.parse("10:15:30.100000Z"), type.read(token("10:15:30.100000Z")));
        assertThrows(AtomValidationException.class, () -> type.read(token("10:15:30.1234Z")));
    }

    /** meta.tn's own worked example, which counting written digits refuses and the value rule admits. */
    @Test
    void aTrailingZeroSpellingOfAnAdmittedValueIsAdmitted() {
        TimeParser tenths = new TimeParser(new TimeType(Optional.empty(), Optional.empty(),
                Optional.of(BigInteger.ONE)));
        assertEquals(OffsetTime.parse("12:00:00.5Z"), tenths.read(token("12:00:00.500Z")));
        assertThrows(AtomValidationException.class, () -> tenths.read(token("12:00:00.51Z")));
    }

    /**
     * A fraction finer than a nanosecond is refused <b>by name</b>. {@code java.time} stops at nine digits
     * on its own, so the cap was always enforced -- but as a shape error quoting a character index, which
     * says the timestamp is malformed rather than that it is finer than the format carries.
     */
    @Test
    void aFractionFinerThanANanosecondIsNamedAsSuch() {
        AtomParseException refused = assertThrows(AtomParseException.class,
                () -> TimeParser.UNCONSTRAINED.read(token("10:15:30.1234567890Z")));
        assertTrue(refused.getMessage().contains("fractional-second digits"), refused.getMessage());
        assertEquals("at most 9 fractional-second digits", refused.expected());
    }

    /** {@code precision: 0} admits no fractional part at all (§5.5). */
    @Test
    void precisionZeroAdmitsNoFraction() {
        TimeParser type = new TimeParser(new TimeType(Optional.empty(), Optional.empty(),
                Optional.of(BigInteger.ZERO)));
        assertEquals(OffsetTime.parse("10:15:30Z"), type.read(token("10:15:30Z")));
        assertThrows(AtomValidationException.class, () -> type.read(token("10:15:30.1Z")));
    }
}
