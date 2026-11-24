package io.ltr8.tson.schema.temporal;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code time} or {@code datetime} bound is a value whose offset is a spelling ([TSON-SCHEMA] §5.5), so a
 * refinement restating its source's bound in another offset restates it, and one whose local time reads inward
 * while its instant lies outward widens.
 */
class InstantBoundRefinementTest {

    private static Optional<OffsetTime> time(String text) {
        return Optional.of(OffsetTime.parse(text));
    }

    private static Optional<OffsetDateTime> datetime(String text) {
        return Optional.of(OffsetDateTime.parse(text));
    }

    private static final Optional<OffsetTime> NO_TIME = Optional.empty();
    private static final Optional<OffsetDateTime> NO_DATETIME = Optional.empty();
    private static final Optional<Long> NO_DIGITS = Optional.empty();

    private static TimeType times(Optional<OffsetTime> min, Optional<OffsetTime> exclusiveMin, Optional<OffsetTime> max,
            Optional<OffsetTime> exclusiveMax) {
        return new TimeType(min, exclusiveMin, max, exclusiveMax, NO_DIGITS);
    }

    private static DateTimeType datetimes(Optional<OffsetDateTime> min, Optional<OffsetDateTime> exclusiveMin,
            Optional<OffsetDateTime> max, Optional<OffsetDateTime> exclusiveMax) {
        return new DateTimeType(min, exclusiveMin, max, exclusiveMax, NO_DIGITS);
    }

    private static void assertRefusedWith(List<String> violations, String fragment) {
        assertTrue(violations.stream().anyMatch(v -> v.contains(fragment)),
                () -> "expected a violation containing '" + fragment + "', got " + violations);
    }

    @Test
    void aTimeBoundRestatedInAnotherOffsetIsARestatement() {
        assertEquals(List.of(), times(time("11:00:00+01:00"), NO_TIME, NO_TIME, NO_TIME)
                .constraintsCheck(times(time("10:00:00Z"), NO_TIME, NO_TIME, NO_TIME)));
        assertEquals(List.of(), times(NO_TIME, time("11:00:00+01:00"), NO_TIME, NO_TIME)
                .constraintsCheck(times(NO_TIME, time("10:00:00Z"), NO_TIME, NO_TIME)));
        assertEquals(List.of(), times(NO_TIME, NO_TIME, time("10:00:00Z"), NO_TIME)
                .constraintsCheck(times(NO_TIME, NO_TIME, time("11:00:00+01:00"), NO_TIME)));
        assertEquals(List.of(), times(NO_TIME, NO_TIME, NO_TIME, time("10:00:00Z"))
                .constraintsCheck(times(NO_TIME, NO_TIME, NO_TIME, time("11:00:00+01:00"))));
    }

    @Test
    void aTimeBoundIsTheTimeOfDayInUtcSoAnOffsetPastMidnightWraps() {
        assertEquals(List.of(), times(time("23:30:00-02:00"), NO_TIME, NO_TIME, NO_TIME)
                .constraintsCheck(times(time("01:30:00Z"), NO_TIME, NO_TIME, NO_TIME)));
        assertEquals(List.of(), times(NO_TIME, NO_TIME, time("01:30:00Z"), NO_TIME)
                .constraintsCheck(times(NO_TIME, NO_TIME, time("23:30:00-02:00"), NO_TIME)));
    }

    @Test
    void aTimeBoundWideningAcrossOffsetsIsRefused() {
        // 10:30+01:00 is 09:30 UTC: later on its own clock, earlier as a value.
        assertRefusedWith(times(time("10:00:00Z"), NO_TIME, NO_TIME, NO_TIME)
                .constraintsCheck(times(time("10:30:00+01:00"), NO_TIME, NO_TIME, NO_TIME)), "is below the source's own");
        assertRefusedWith(times(NO_TIME, NO_TIME, time("10:00:00Z"), NO_TIME)
                .constraintsCheck(times(NO_TIME, NO_TIME, time("09:30:00-01:00"), NO_TIME)), "is above the source's own");
        // An inclusive end at the instant an exclusive source end removes admits that instant back.
        assertRefusedWith(times(NO_TIME, time("10:00:00Z"), NO_TIME, NO_TIME)
                .constraintsCheck(times(time("11:00:00+01:00"), NO_TIME, NO_TIME, NO_TIME)), "is below the source's own");
        assertEquals(List.of(), times(time("10:00:00Z"), NO_TIME, NO_TIME, NO_TIME)
                .constraintsCheck(times(NO_TIME, time("11:00:00+01:00"), NO_TIME, NO_TIME)));
    }

    @Test
    void aDatetimeBoundRestatedInAnotherOffsetIsARestatement() {
        Optional<OffsetDateTime> utc = datetime("2026-01-01T10:00:00Z");
        Optional<OffsetDateTime> plusOne = datetime("2026-01-01T11:00:00+01:00");
        assertEquals(List.of(), datetimes(plusOne, NO_DATETIME, NO_DATETIME, NO_DATETIME)
                .constraintsCheck(datetimes(utc, NO_DATETIME, NO_DATETIME, NO_DATETIME)));
        assertEquals(List.of(), datetimes(NO_DATETIME, plusOne, NO_DATETIME, NO_DATETIME)
                .constraintsCheck(datetimes(NO_DATETIME, utc, NO_DATETIME, NO_DATETIME)));
        assertEquals(List.of(), datetimes(NO_DATETIME, NO_DATETIME, utc, NO_DATETIME)
                .constraintsCheck(datetimes(NO_DATETIME, NO_DATETIME, plusOne, NO_DATETIME)));
        assertEquals(List.of(), datetimes(NO_DATETIME, NO_DATETIME, NO_DATETIME, utc)
                .constraintsCheck(datetimes(NO_DATETIME, NO_DATETIME, NO_DATETIME, plusOne)));
    }

    @Test
    void aDatetimeBoundWideningAcrossOffsetsIsRefused() {
        Optional<OffsetDateTime> utc = datetime("2026-01-01T10:00:00Z");
        assertRefusedWith(datetimes(utc, NO_DATETIME, NO_DATETIME, NO_DATETIME)
                .constraintsCheck(datetimes(datetime("2026-01-01T10:30:00+01:00"), NO_DATETIME, NO_DATETIME,
                        NO_DATETIME)), "is below the source's own");
        assertRefusedWith(datetimes(NO_DATETIME, NO_DATETIME, utc, NO_DATETIME)
                .constraintsCheck(datetimes(NO_DATETIME, NO_DATETIME, datetime("2026-01-01T09:30:00-01:00"),
                        NO_DATETIME)), "is above the source's own");
        assertRefusedWith(datetimes(NO_DATETIME, NO_DATETIME, NO_DATETIME, utc)
                .constraintsCheck(datetimes(NO_DATETIME, NO_DATETIME, datetime("2026-01-01T11:00:00+01:00"),
                        NO_DATETIME)), "is above the source's own");
        assertEquals(List.of(), datetimes(NO_DATETIME, NO_DATETIME, utc, NO_DATETIME)
                .constraintsCheck(datetimes(NO_DATETIME, NO_DATETIME, NO_DATETIME,
                        datetime("2026-01-01T11:00:00+01:00"))));
    }
}
