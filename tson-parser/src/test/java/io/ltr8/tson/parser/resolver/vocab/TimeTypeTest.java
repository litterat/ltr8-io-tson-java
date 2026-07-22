package io.ltr8.tson.parser.resolver.vocab;

import io.ltr8.tson.parser.ast.TokenForm;
import io.ltr8.tson.parser.ast.TokenValue;
import org.junit.jupiter.api.Test;

import java.time.OffsetTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TimeTypeTest {

    private static TokenValue token(String text) {
        return new TokenValue(text, TokenForm.UNQUOTED);
    }

    @Test
    void acceptsUtcTime() {
        assertEquals(OffsetTime.parse("10:15:30Z"), TimeType.UNCONSTRAINED.read(token("10:15:30Z")));
    }

    @Test
    void acceptsLowercaseZ() {
        // RFC 3339 explicitly allows lowercase 'z' -- OffsetTime.parse already gets this right
        // natively, confirmed empirically.
        assertEquals(OffsetTime.parse("10:15:30Z"), TimeType.UNCONSTRAINED.read(token("10:15:30z")));
    }

    @Test
    void acceptsNumericOffset() {
        assertEquals(OffsetTime.parse("10:15:30+05:30"), TimeType.UNCONSTRAINED.read(token("10:15:30+05:30")));
    }

    @Test
    void acceptsFractionalSeconds() {
        assertEquals(OffsetTime.parse("10:15:30.123Z"), TimeType.UNCONSTRAINED.read(token("10:15:30.123Z")));
    }

    @Test
    void missingOffsetIsRejected() {
        assertThrows(AtomParseException.class, () -> TimeType.UNCONSTRAINED.read(token("10:15:30")));
    }

    @Test
    void invalidHourIsRejected() {
        assertThrows(AtomParseException.class, () -> TimeType.UNCONSTRAINED.read(token("24:00:00Z")));
    }

    @Test
    void leapSecondIsRejected() {
        // RFC 3339's grammar permits time-second up to 60 for leap-second accommodation, but
        // java.time has no leap-second concept at all -- documented limitation, not fixed here.
        assertThrows(AtomParseException.class, () -> TimeType.UNCONSTRAINED.read(token("23:59:60Z")));
    }

    @Test
    void nonTimeTokenIsAParseError() {
        assertThrows(AtomParseException.class, () -> TimeType.UNCONSTRAINED.read(token("not-a-time")));
    }

    @Test
    void minRejectsEarlierTime() {
        TimeType type = new TimeType(Optional.of(OffsetTime.parse("09:00:00Z")), Optional.empty());
        assertEquals(OffsetTime.parse("09:00:00Z"), type.read(token("09:00:00Z")));
        assertThrows(AtomValidationException.class, () -> type.read(token("08:59:59Z")));
    }

    @Test
    void maxRejectsLaterTime() {
        TimeType type = new TimeType(Optional.empty(), Optional.of(OffsetTime.parse("09:00:00Z")));
        assertEquals(OffsetTime.parse("09:00:00Z"), type.read(token("09:00:00Z")));
        assertThrows(AtomValidationException.class, () -> type.read(token("09:00:01Z")));
    }
}
