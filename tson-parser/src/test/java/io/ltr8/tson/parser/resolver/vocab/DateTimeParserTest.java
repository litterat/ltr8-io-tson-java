package io.ltr8.tson.parser.resolver.vocab;

import io.ltr8.tson.parser.ast.TokenForm;
import io.ltr8.tson.parser.ast.TokenValue;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
