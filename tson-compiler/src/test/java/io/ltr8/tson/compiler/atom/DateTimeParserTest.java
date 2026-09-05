package io.ltr8.tson.compiler.atom;

import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.schema.meta.DateTimeType;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DateTimeParserTest {

    private static TokenValue token(String text) {
        return new TokenValue(text, TokenForm.UNQUOTED);
    }

    @Test
    void acceptsUtcDateTime() {
        assertEquals(OffsetDateTime.parse("2025-03-13T10:15:30Z"),
                DateTimeParser.UNCONSTRAINED.read(token("2025-03-13T10:15:30Z")));
    }

    @Test
    void acceptsLowercaseTAndZ() {
        // RFC 3339 explicitly allows lowercase 't'/'z' -- OffsetDateTime.parse already gets this
        // right natively, confirmed empirically.
        assertEquals(OffsetDateTime.parse("2025-03-13T10:15:30Z"),
                DateTimeParser.UNCONSTRAINED.read(token("2025-03-13t10:15:30z")));
    }

    @Test
    void acceptsNumericOffset() {
        assertEquals(OffsetDateTime.parse("2025-03-13T10:15:30+05:30"),
                DateTimeParser.UNCONSTRAINED.read(token("2025-03-13T10:15:30+05:30")));
    }

    @Test
    void extendedYearFormatIsRejectedEvenThoughOffsetDateTimeParseAcceptsIt() {
        assertThrows(AtomParseException.class,
                () -> DateTimeParser.UNCONSTRAINED.read(token("+12025-03-13T10:00:00Z")));
    }

    @Test
    void missingOffsetIsRejected() {
        assertThrows(AtomParseException.class, () -> DateTimeParser.UNCONSTRAINED.read(token("2025-03-13T10:15:30")));
    }

    @Test
    void spaceInsteadOfTIsRejected() {
        assertThrows(AtomParseException.class, () -> DateTimeParser.UNCONSTRAINED.read(token("2025-03-13 10:15:30Z")));
    }

    @Test
    void nonDateTimeTokenIsAParseError() {
        assertThrows(AtomParseException.class, () -> DateTimeParser.UNCONSTRAINED.read(token("not-a-datetime")));
    }

    @Test
    void minRejectsEarlierDateTime() {
        DateTimeParser type = new DateTimeParser(Optional.of(OffsetDateTime.parse("2025-01-01T00:00:00Z")), Optional.empty());
        assertEquals(OffsetDateTime.parse("2025-01-01T00:00:00Z"), type.read(token("2025-01-01T00:00:00Z")));
        assertThrows(AtomValidationException.class, () -> type.read(token("2024-12-31T23:59:59Z")));
    }

    @Test
    void maxRejectsLaterDateTime() {
        DateTimeParser type = new DateTimeParser(Optional.empty(), Optional.of(OffsetDateTime.parse("2025-01-01T00:00:00Z")));
        assertEquals(OffsetDateTime.parse("2025-01-01T00:00:00Z"), type.read(token("2025-01-01T00:00:00Z")));
        assertThrows(AtomValidationException.class, () -> type.read(token("2025-01-01T00:00:01Z")));
    }

    @Test
    void writeRoundTripsThroughRead() {
        assertEquals("2025-03-13T10:15:30Z",
                DateTimeParser.UNCONSTRAINED.write(DateTimeParser.UNCONSTRAINED.read(token("2025-03-13T10:15:30Z"))));
    }

    /**
     * <b>{@code precision} constrains the value, not the spelling</b> (§5.5) -- the same rule {@code
     * TimeParser} applies, inherited from the same {@code full-time} production: an instant that is a whole
     * number of 10⁻ᴺ seconds, however many digits an encoding spelled it with. Exact either way; an instant
     * off the grid is refused rather than rounded onto it.
     */
    @Test
    void precisionConstrainsTheValueNotTheSpelling() {
        DateTimeParser type = new DateTimeParser(new DateTimeType(Optional.empty(), Optional.empty(),
                Optional.of(BigInteger.valueOf(3))));
        assertEquals(OffsetDateTime.parse("2025-03-13T10:15:30Z"), type.read(token("2025-03-13T10:15:30Z")));
        assertEquals(OffsetDateTime.parse("2025-03-13T10:15:30.100Z"),
                type.read(token("2025-03-13T10:15:30.100Z")));
        assertEquals(OffsetDateTime.parse("2025-03-13T10:15:30.100000Z"),
                type.read(token("2025-03-13T10:15:30.100000Z")));
        assertThrows(AtomValidationException.class, () -> type.read(token("2025-03-13T10:15:30.1234Z")));
    }

    /**
     * A fraction finer than a nanosecond is refused <b>by name</b>. {@code java.time} stops at nine digits
     * on its own, so the cap was always enforced -- but as a shape error quoting a character index, which
     * says the timestamp is malformed rather than that it is finer than the format carries.
     */
    @Test
    void aFractionFinerThanANanosecondIsNamedAsSuch() {
        AtomParseException refused = assertThrows(AtomParseException.class,
                () -> DateTimeParser.UNCONSTRAINED.read(token("2025-03-13T10:15:30.1234567890Z")));
        assertTrue(refused.getMessage().contains("fractional-second digits"), refused.getMessage());
        assertEquals("at most 9 fractional-second digits", refused.expected());
    }

    /** {@code precision: 0} admits no fractional part at all (§5.5). */
    @Test
    void precisionZeroAdmitsNoFraction() {
        DateTimeParser type = new DateTimeParser(new DateTimeType(Optional.empty(), Optional.empty(),
                Optional.of(BigInteger.ZERO)));
        assertEquals(OffsetDateTime.parse("2025-03-13T10:15:30Z"), type.read(token("2025-03-13T10:15:30Z")));
        assertThrows(AtomValidationException.class, () -> type.read(token("2025-03-13T10:15:30.1Z")));
    }
}
