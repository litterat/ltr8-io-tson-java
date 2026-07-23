package io.ltr8.tson.parser.resolver.vocab;

import io.ltr8.tson.parser.ast.TokenForm;
import io.ltr8.tson.parser.ast.TokenValue;
import org.junit.jupiter.api.Test;

import java.time.OffsetTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
