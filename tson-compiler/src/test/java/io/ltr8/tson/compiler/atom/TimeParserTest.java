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
     * <b>{@code precision} bounds the fractional-second digits from above, on the token as written</b>
     * (§5.5). Judged textually and never on the parsed value: {@code 10:15:30.100Z} states three digits
     * whatever instant it denotes, and the atom is exact, so a token over the bound is refused rather than
     * truncated to fit.
     */
    @Test
    void precisionBoundsTheWrittenFractionalDigits() {
        TimeParser type = new TimeParser(new TimeType(Optional.empty(), Optional.empty(),
                Optional.of(BigInteger.valueOf(3))));
        assertEquals(OffsetTime.parse("10:15:30Z"), type.read(token("10:15:30Z")));
        assertEquals(OffsetTime.parse("10:15:30.123Z"), type.read(token("10:15:30.123Z")));
        // Three written digits, one significant -- the count is of the token, not of the instant.
        assertEquals(OffsetTime.parse("10:15:30.100Z"), type.read(token("10:15:30.100Z")));
        assertThrows(AtomValidationException.class, () -> type.read(token("10:15:30.1234Z")));
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
