package io.ltr8.tson.parser.resolver.vocab;

import io.ltr8.tson.parser.ast.TokenForm;
import io.ltr8.tson.parser.ast.TokenValue;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Period;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DurationTypeTest {

    private static TokenValue token(String text) {
        return new TokenValue(text, TokenForm.UNQUOTED);
    }

    @Test
    void acceptsPureClockDuration() {
        IsoDuration d = DurationType.UNCONSTRAINED.read(token("PT1H30M"));
        assertEquals(Period.ZERO, d.calendarPart());
        assertEquals(Duration.ofMinutes(90), d.clockPart());
    }

    @Test
    void acceptsPureCalendarDuration() {
        IsoDuration d = DurationType.UNCONSTRAINED.read(token("P1Y2M3D"));
        assertEquals(Period.of(1, 2, 3), d.calendarPart());
        assertEquals(Duration.ZERO, d.clockPart());
    }

    @Test
    void acceptsCombinedCalendarAndClockDuration() {
        // Neither java.time.Period.parse nor java.time.Duration.parse alone covers this form --
        // confirmed empirically before writing DurationType.
        IsoDuration d = DurationType.UNCONSTRAINED.read(token("P1Y2M3DT4H5M6S"));
        assertEquals(Period.of(1, 2, 3), d.calendarPart());
        assertEquals(Duration.ofHours(4).plusMinutes(5).plusSeconds(6), d.clockPart());
    }

    @Test
    void acceptsFractionalSeconds() {
        IsoDuration d = DurationType.UNCONSTRAINED.read(token("PT1.5S"));
        assertEquals(Duration.ofSeconds(1, 500_000_000), d.clockPart());
    }

    @Test
    void singleDesignatorForms() {
        assertEquals(Period.of(1, 0, 0), DurationType.UNCONSTRAINED.read(token("P1Y")).calendarPart());
        assertEquals(Period.of(0, 1, 0), DurationType.UNCONSTRAINED.read(token("P1M")).calendarPart());
        assertEquals(Period.of(0, 0, 1), DurationType.UNCONSTRAINED.read(token("P1D")).calendarPart());
        assertEquals(Duration.ofHours(1), DurationType.UNCONSTRAINED.read(token("PT1H")).clockPart());
        assertEquals(Duration.ofSeconds(1), DurationType.UNCONSTRAINED.read(token("PT1S")).clockPart());
    }

    @Test
    void barePAloneIsRejected() {
        assertThrows(AtomParseException.class, () -> DurationType.UNCONSTRAINED.read(token("P")));
    }

    @Test
    void barePTAloneIsRejected() {
        assertThrows(AtomParseException.class, () -> DurationType.UNCONSTRAINED.read(token("PT")));
    }

    @Test
    void lowercaseIsRejectedEvenThoughDurationParseAcceptsIt() {
        // Duration.parse("pt1h") succeeds on its own -- ISO 8601's PnYnMnDTnHnMnS notation implies
        // uppercase only. Confirmed empirically before writing DurationType.
        assertThrows(AtomParseException.class, () -> DurationType.UNCONSTRAINED.read(token("pt1h")));
    }

    @Test
    void leadingSignIsRejectedEvenThoughDurationAndPeriodParseAcceptIt() {
        // Duration.parse("PT-1H") and Period.parse("P-1Y") both succeed on their own -- TSON's
        // PnYnMnDTnHnMnS notation shows no leading sign. Confirmed empirically before writing
        // DurationType.
        assertThrows(AtomParseException.class, () -> DurationType.UNCONSTRAINED.read(token("-P1Y")));
        assertThrows(AtomParseException.class, () -> DurationType.UNCONSTRAINED.read(token("PT-1H")));
    }

    @Test
    void weekFormIsNotAccepted() {
        // ISO 8601 also defines a PnW alternative form -- TSON's own table gives the format as
        // literally PnYnMnDTnHnMnS with no W, read here as exhaustive. See SPEC-FEEDBACK.md #12.
        assertThrows(AtomParseException.class, () -> DurationType.UNCONSTRAINED.read(token("P3W")));
    }

    @Test
    void wrongOrderOfDesignatorsIsRejected() {
        assertThrows(AtomParseException.class, () -> DurationType.UNCONSTRAINED.read(token("P1D1Y")));
    }

    @Test
    void nonDurationTokenIsAParseError() {
        assertThrows(AtomParseException.class, () -> DurationType.UNCONSTRAINED.read(token("not-a-duration")));
    }

    // ── write() ──────────────────────────────────────────────────────────

    @Test
    void writeRoundTripsThroughRead() {
        assertEquals("P1Y2M3DT4H5M6S",
                DurationType.UNCONSTRAINED.write(DurationType.UNCONSTRAINED.read(token("P1Y2M3DT4H5M6S"))));
    }

    @Test
    void writeOmitsZeroDesignators() {
        assertEquals("P1D", DurationType.UNCONSTRAINED.write(DurationType.UNCONSTRAINED.read(token("P1D"))));
        assertEquals("PT1H", DurationType.UNCONSTRAINED.write(DurationType.UNCONSTRAINED.read(token("PT1H"))));
    }

    @Test
    void writeAllZeroStillEmitsAtLeastOneDesignator() {
        // read() itself rejects a bare "P"/"PT" -- but an IsoDuration constructed directly (not
        // via read()) could still be all-zero, and write() must still produce something read()
        // accepts back rather than an incomplete "P".
        IsoDuration allZero = new IsoDuration(Period.ZERO, Duration.ZERO);
        String written = DurationType.UNCONSTRAINED.write(allZero);
        assertEquals(allZero, DurationType.UNCONSTRAINED.read(token(written)));
    }

    @Test
    void writePreservesFractionalSeconds() {
        IsoDuration value = DurationType.UNCONSTRAINED.read(token("PT1.5S"));
        assertEquals("PT1.5S", DurationType.UNCONSTRAINED.write(value));
    }

    @Test
    void writeStripsTrailingZerosFromTheFraction() {
        IsoDuration value = new IsoDuration(Period.ZERO, Duration.ofSeconds(1, 500_000_000));
        assertEquals("PT1.5S", DurationType.UNCONSTRAINED.write(value));
    }

    @Test
    void writeDoesNotDayNormalizeAnUnrolledClockPart() {
        // A 30-hour Duration must write as "PT30H", not silently wrap to "PT6H" the way
        // toHoursPart() (day-normalized) would -- the regression case this method's Javadoc
        // documents explicitly.
        IsoDuration thirtyHours = new IsoDuration(Period.ZERO, Duration.ofHours(30));
        assertEquals("PT30H", DurationType.UNCONSTRAINED.write(thirtyHours));
    }
}
