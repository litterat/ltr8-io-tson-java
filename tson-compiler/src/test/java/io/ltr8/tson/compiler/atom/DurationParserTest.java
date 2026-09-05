package io.ltr8.tson.compiler.atom;

import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.schema.meta.DurationType;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code duration}: elapsed time, as a signed number of seconds ([TSON-SCHEMA] §5.5).
 *
 * <p>The grammar is RFC 3339 Appendix A's with three extensions -- a leading sign, a fraction on the seconds
 * component only, and any subset of components in order -- and one restriction: no Y and no month-M. That
 * restriction is the point. While one type carried calendar and clock components together its order was
 * partial, and the bounds it declared could not be enforced at all; with the calendar half moved to {@code
 * period} every value is a fixed number of seconds, so bounds mean something and are checked here.
 */
class DurationParserTest {

    private static Duration read(AtomType<Duration> parser, String text) {
        return parser.read(new TokenValue(text, TokenForm.UNQUOTED));
    }

    private static Duration read(String text) {
        return read(DurationParser.UNCONSTRAINED, text);
    }

    // ── The grammar ──────────────────────────────────────────────────────

    @Test
    void readsTheClockComponents() {
        assertEquals(Duration.ofSeconds(4 * 3600 + 5 * 60 + 6), read("PT4H5M6S"));
        assertEquals(Duration.ofHours(1), read("PT1H"));
        assertEquals(Duration.ofMinutes(1), read("PT1M"));
    }

    /** A day is exactly 86400 s and a week 7 days, so the three spellings below are one value. */
    @Test
    void aDayAndAWeekAreFixedLengths() {
        assertEquals(Duration.ofDays(21), read("P21D"));
        assertEquals(Duration.ofDays(21), read("P3W"));
        assertEquals(Duration.ofDays(21), read("PT504H"));
    }

    /** Any subset in order, which is how {@link Duration} itself writes a value with no minutes. */
    @Test
    void aComponentMayBeOmittedProvidedOrderIsKept() {
        assertEquals(Duration.ofSeconds(3630), read("PT1H30S"));
        assertEquals(Duration.ofSeconds(86_401), read("P1DT1S"));
    }

    @Test
    void theLeadingSignIsAdmitted() {
        assertEquals(Duration.ofHours(-1), read("-PT1H"));
        assertEquals(Duration.ZERO, read("-PT0S"));
        assertEquals(read("PT0S"), read("P0D"));
    }

    @Test
    void aFractionIsAdmittedOnTheSecondsComponentOnly() {
        assertEquals(Duration.ofMillis(500), read("PT0.5S"));
        assertThrows(AtomParseException.class, () -> read("PT1.5H"));
        assertThrows(AtomParseException.class, () -> read("PT0,5S"));
    }

    /**
     * The restriction the split exists for: a year and a month have no fixed length, so they are not
     * durations. {@code P1M} is one month and belongs to {@code period}; a minute is {@code PT1M}.
     */
    @Test
    void theCalendarComponentsAreNotAdmitted() {
        assertThrows(AtomParseException.class, () -> read("P1Y"));
        assertThrows(AtomParseException.class, () -> read("P1M"));
        assertThrows(AtomParseException.class, () -> read("P1Y2M3DT4H5M6S"));
    }

    /** {@code PnW} stands alone in the ABNF. */
    @Test
    void theWeekFormDoesNotCombine() {
        assertThrows(AtomParseException.class, () -> read("P1W2D"));
        assertThrows(AtomParseException.class, () -> read("P1WT1H"));
    }

    @Test
    void atLeastOneComponentIsRequired() {
        assertThrows(AtomParseException.class, () -> read("P"));
        assertThrows(AtomParseException.class, () -> read("PT"));
    }

    @Test
    void lowercaseIsRejected() {
        assertThrows(AtomParseException.class, () -> read("pt1h"));
        assertThrows(AtomParseException.class, () -> read("P1dT1H"));
    }

    // ── The constraints, which the old partial order could not carry ─────

    @Test
    void boundsCompareTheValueAndNotTheToken() {
        AtomType<Duration> atLeastAnHour =
                new DurationParser(Optional.of(Duration.ofHours(1)), Optional.empty());

        assertEquals(Duration.ofMinutes(90), read(atLeastAnHour, "PT90M"));
        assertEquals(Duration.ofMinutes(90), read(atLeastAnHour, "PT1H30M"));
        assertThrows(AtomValidationException.class, () -> read(atLeastAnHour, "PT59M"));
    }

    @Test
    void anExclusiveBoundExcludesItsOwnValue() {
        AtomType<Duration> overAnHour = new DurationParser(new DurationType(Optional.empty(),
                Optional.of(Duration.ofHours(1)), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty()));

        assertEquals(Duration.ofSeconds(3601), read(overAnHour, "PT3601S"));
        assertThrows(AtomValidationException.class, () -> read(overAnHour, "PT1H"));
    }

    /**
     * {@code precision: N} is {@code fraction_digits: N} on the seconds count, which §5.5 makes a constraint
     * on the value and not on the spelling -- {@code PT0.250S} is a whole number of hundredths however it
     * was typed, and the same rule {@code time_type}'s own {@code @doc} spells out.
     */
    @Test
    void precisionConstrainsTheSecondsCountNotTheSpelling() {
        AtomType<Duration> twoDigits = new DurationParser(new DurationType(Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(BigInteger.TWO), Optional.empty()));

        assertEquals(Duration.ofMillis(250), read(twoDigits, "PT0.25S"));
        assertEquals(Duration.ofMillis(250), read(twoDigits, "PT0.250S"));
        assertEquals(Duration.ofMillis(250), read(twoDigits, "PT0.2500S"));
        assertThrows(AtomValidationException.class, () -> read(twoDigits, "PT0.255S"));
    }

    // ── the value space's own two ends (§5.5) ────────────────────────────

    /**
     * A signed 64-bit count of nanoseconds, both ends. Below it, {@code time-secfrac}'s unbounded {@code
     * 1*DIGIT} names a value no host runtime has a type for -- a nanosecond is the finest any of them
     * offers and several are coarser. Above it, the tightest host is a signed 64-bit nanosecond count
     * exactly, and a range that depends on which implementation read the document is not a range.
     */
    @Test
    void aFractionFinerThanANanosecondIsNotADuration() {
        assertEquals(Duration.ofNanos(1), read(DurationParser.UNCONSTRAINED, "PT0.000000001S"));
        assertThrows(AtomParseException.class,
                () -> read(DurationParser.UNCONSTRAINED, "PT0.0000000001S"));
    }

    @Test
    void aMagnitudePastTheCeilingIsNotADuration() {
        // The last admitted value and the first refused one, on both signs.
        assertEquals(Duration.ofSeconds(9_223_372_036L, 854_775_807L),
                read(DurationParser.UNCONSTRAINED, "PT9223372036.854775807S"));
        assertEquals(Duration.ofSeconds(9_223_372_036L, 854_775_807L).negated(),
                read(DurationParser.UNCONSTRAINED, "-PT9223372036.854775807S"));
        assertThrows(AtomValidationException.class,
                () -> read(DurationParser.UNCONSTRAINED, "PT9223372036.854775808S"));
        assertThrows(AtomValidationException.class,
                () -> read(DurationParser.UNCONSTRAINED, "-PT9223372036.854775808S"));
    }

    @Test
    void theCeilingIsBelowWhatTheHostTypeWouldTake() {
        // java.time.Duration reaches ±292 *billion* years, so nothing about the host refuses this -- only
        // the value space does, which is the whole point of the ceiling being normative rather than
        // whatever the implementation happens to hold.
        assertEquals(400_000L * 86_400L, Duration.ofDays(400_000).getSeconds());
        assertThrows(AtomValidationException.class, () -> read(DurationParser.UNCONSTRAINED, "P400000D"));
    }

    /**
     * {@code multiple_of} tests on {@code Duration.toNanos}, which throws past ±292 years. The ceiling is
     * exactly that range, so the overflow is unreachable rather than merely unlikely: a value that would
     * have hit it is refused before the facet is asked.
     */
    @Test
    void aValuePastTheCeilingIsRefusedBeforeMultipleOfCanOverflow() {
        AtomType<Duration> quarterHours = new DurationParser(new DurationType(Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(Duration.ofMinutes(15))));

        assertThrows(AtomValidationException.class, () -> read(quarterHours, "PT2562047788015215H"));
        // And a span inside the ceiling still gets a real verdict from the facet.
        assertEquals(Duration.ofDays(100_000), read(quarterHours, "P100000D"));
    }

    /** Sign is ignored for the test, so a negative span is a multiple of a positive step. */
    @Test
    void multipleOfIgnoresSign() {
        AtomType<Duration> quarterHours = new DurationParser(new DurationType(Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(Duration.ofMinutes(15))));

        assertEquals(Duration.ofMinutes(30), read(quarterHours, "PT30M"));
        assertEquals(Duration.ofMinutes(-30), read(quarterHours, "-PT30M"));
        assertThrows(AtomValidationException.class, () -> read(quarterHours, "PT20M"));
    }

    // ── Writing ──────────────────────────────────────────────────────────

    @Test
    void writesTheValueBackInDurationsOwnForm() {
        assertEquals("PT0S", DurationParser.UNCONSTRAINED.write(Duration.ZERO));
        assertEquals("PT1H30M", DurationParser.UNCONSTRAINED.write(Duration.ofMinutes(90)));
        assertEquals("PT504H", DurationParser.UNCONSTRAINED.write(Duration.ofDays(21)));
        assertEquals("-PT1H", DurationParser.UNCONSTRAINED.write(Duration.ofHours(-1)));
        assertEquals("PT0.5S", DurationParser.UNCONSTRAINED.write(Duration.ofMillis(500)));
    }

    @Test
    void everySpellingOfOneValueWritesBackTheSameWay() {
        for (String spelling : new String[] {"P3W", "P21D", "PT504H"}) {
            assertEquals("PT504H", DurationParser.UNCONSTRAINED.write(read(spelling)), spelling);
        }
    }
}
