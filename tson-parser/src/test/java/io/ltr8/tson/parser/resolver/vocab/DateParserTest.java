package io.ltr8.tson.parser.resolver.vocab;

import io.ltr8.tson.parser.ast.TokenForm;
import io.ltr8.tson.parser.ast.TokenValue;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DateParserTest {

    private static TokenValue token(String text) {
        return new TokenValue(text, TokenForm.UNQUOTED);
    }

    @Test
    void acceptsPlainDate() {
        assertEquals(LocalDate.of(2025, 3, 13), DateParser.UNCONSTRAINED.read(token("2025-03-13")));
    }

    @Test
    void rejectsInvalidLeapDay() {
        assertThrows(AtomParseException.class, () -> DateParser.UNCONSTRAINED.read(token("2023-02-29")));
    }

    @Test
    void acceptsValidLeapDay() {
        assertEquals(LocalDate.of(2024, 2, 29), DateParser.UNCONSTRAINED.read(token("2024-02-29")));
    }

    @Test
    void rejectsInvalidMonth() {
        assertThrows(AtomParseException.class, () -> DateParser.UNCONSTRAINED.read(token("2025-13-01")));
    }

    @Test
    void extendedYearFormatIsRejectedEvenThoughLocalDateParseAcceptsIt() {
        // LocalDate.parse("+12025-03-13") succeeds on its own (ISO 8601 extended year) -- RFC
        // 3339's full-date grammar requires exactly a 4-digit year, no sign. Confirmed empirically
        // before writing DateParser.
        assertThrows(AtomParseException.class, () -> DateParser.UNCONSTRAINED.read(token("+12025-03-13")));
    }

    @Test
    void unpaddedComponentsAreRejected() {
        assertThrows(AtomParseException.class, () -> DateParser.UNCONSTRAINED.read(token("2025-3-13")));
    }

    @Test
    void twoDigitYearIsRejected() {
        assertThrows(AtomParseException.class, () -> DateParser.UNCONSTRAINED.read(token("25-03-13")));
    }

    @Test
    void nonDateTokenIsAParseError() {
        assertThrows(AtomParseException.class, () -> DateParser.UNCONSTRAINED.read(token("not-a-date")));
    }

    @Test
    void minRejectsEarlierDate() {
        DateParser type = new DateParser(Optional.of(LocalDate.of(2025, 1, 1)), Optional.empty());
        assertEquals(LocalDate.of(2025, 1, 1), type.read(token("2025-01-01")));
        assertThrows(AtomValidationException.class, () -> type.read(token("2024-12-31")));
    }

    @Test
    void maxRejectsLaterDate() {
        DateParser type = new DateParser(Optional.empty(), Optional.of(LocalDate.of(2025, 1, 1)));
        assertEquals(LocalDate.of(2025, 1, 1), type.read(token("2025-01-01")));
        assertThrows(AtomValidationException.class, () -> type.read(token("2025-01-02")));
    }

    @Test
    void writeRoundTripsThroughRead() {
        assertEquals("2025-03-13", DateParser.UNCONSTRAINED.write(DateParser.UNCONSTRAINED.read(token("2025-03-13"))));
    }
}
