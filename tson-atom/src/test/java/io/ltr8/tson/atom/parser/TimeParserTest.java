package io.ltr8.tson.atom.parser;

import io.ltr8.tson.atom.AtomParseException;
import io.ltr8.tson.atom.AtomValidationException;
import io.ltr8.tson.schema.temporal.TimeType;
import org.junit.jupiter.api.Test;
import java.time.OffsetTime;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeParserTest {

    private static String token(String text) {
        return text;
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
        // RFC 3339's grammar admits second 60, but a time of day lies on [00:00:00, 24:00:00).
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
    void exclusiveMinRefusesItsOwnInstantAndEveryEarlierOne() {
        TimeParser type = new TimeParser(new TimeType(Optional.empty(), Optional.of(OffsetTime.parse("09:00:00Z")),
                Optional.empty(), Optional.empty(), Optional.empty()));
        AtomValidationException at = assertThrows(AtomValidationException.class, () -> type.read("09:00:00Z"));
        assertEquals("> 09:00Z", at.expected());
        assertEquals(OffsetTime.parse("09:00:00.001Z"), type.read("09:00:00.001Z"));
        assertThrows(AtomValidationException.class, () -> type.read("08:59:59Z"));
    }

    @Test
    void exclusiveMaxRefusesItsOwnInstantAndEveryLaterOne() {
        TimeParser type = new TimeParser(new TimeType(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(OffsetTime.parse("17:00:00Z")), Optional.empty()));
        AtomValidationException at = assertThrows(AtomValidationException.class, () -> type.read("17:00:00Z"));
        assertEquals("< 17:00Z", at.expected());
        assertEquals(OffsetTime.parse("16:59:59Z"), type.read("16:59:59Z"));
        assertThrows(AtomValidationException.class, () -> type.read("17:00:01Z"));
    }

    /** The offset is a spelling (§5.5): the bound's own instant written in another offset is still the bound. */
    @Test
    void anExclusiveBoundExcludesItsInstantInAnyOffset() {
        TimeParser type = new TimeParser(new TimeType(Optional.empty(), Optional.of(OffsetTime.parse("09:00:00Z")),
                Optional.empty(), Optional.empty(), Optional.empty()));
        assertThrows(AtomValidationException.class, () -> type.read("10:00:00+01:00"));
        assertEquals(OffsetTime.parse("10:00:01+01:00"), type.read("10:00:01+01:00"));
    }

    /** A time is its time of day in UTC (§5.5), so {@code 23:30:00-02:00} is {@code 01:30:00Z} and not a day later. */
    @Test
    void aValueWhoseOffsetCarriesItPastMidnightIsJudgedAsItsUtcTimeOfDay() {
        TimeParser beforeTwo = new TimeParser(Optional.empty(), Optional.of(OffsetTime.parse("02:00:00Z")));
        assertEquals(OffsetTime.parse("23:30:00-02:00"), beforeTwo.read("23:30:00-02:00"));
        assertThrows(AtomValidationException.class, () -> beforeTwo.read("00:30:00-02:00"));
        TimeParser afterTen = new TimeParser(Optional.of(OffsetTime.parse("22:00:00Z")), Optional.empty());
        assertEquals(OffsetTime.parse("01:00:00+02:00"), afterTen.read("01:00:00+02:00"));
        assertThrows(AtomValidationException.class, () -> afterTen.read("23:00:00+02:00"));
    }

    @Test
    void writeRoundTripsThroughRead() {
        assertEquals("10:15:30+01:00",
                TimeParser.UNCONSTRAINED.write(TimeParser.UNCONSTRAINED.read(token("10:15:30+01:00"))));
    }

    /**
     * <b>{@code fraction_digits} constrains the value, not the spelling</b> (§5.5): {@code fraction_digits: N}
     * admits an instant that is a whole number of 10⁻ᴺ seconds. temporal.tn's own {@code @doc} gives the example
     * this turns on -- "{@code 12:00:00.500} is admitted under {@code fraction_digits: 1}". The atom is exact
     * either way: an instant genuinely off the grid is refused rather than rounded onto it.
     */
    @Test
    void fractionDigitsConstrainsTheValueNotTheSpelling() {
        TimeParser type = new TimeParser(new TimeType(Optional.empty(), Optional.empty(),
                Optional.of(3L)));
        assertEquals(OffsetTime.parse("10:15:30Z"), type.read(token("10:15:30Z")));
        assertEquals(OffsetTime.parse("10:15:30.123Z"), type.read(token("10:15:30.123Z")));
        // Six written digits and a millisecond value: the grid is what is tested, not the digit count.
        assertEquals(OffsetTime.parse("10:15:30.100000Z"), type.read(token("10:15:30.100000Z")));
        assertThrows(AtomValidationException.class, () -> type.read(token("10:15:30.1234Z")));
    }

    /** numeric.tn's own worked example, which counting written digits refuses and the value rule admits. */
    @Test
    void aTrailingZeroSpellingOfAnAdmittedValueIsAdmitted() {
        TimeParser tenths = new TimeParser(new TimeType(Optional.empty(), Optional.empty(),
                Optional.of(1L)));
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

    /** {@code fraction_digits: 0} admits no fractional part at all (§5.5). */
    @Test
    void fractionDigitsZeroAdmitsNoFraction() {
        TimeParser type = new TimeParser(new TimeType(Optional.empty(), Optional.empty(),
                Optional.of(0L)));
        assertEquals(OffsetTime.parse("10:15:30Z"), type.read(token("10:15:30Z")));
        assertThrows(AtomValidationException.class, () -> type.read(token("10:15:30.1Z")));
    }
}
