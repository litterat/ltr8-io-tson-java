package io.ltr8.tson.compiler.atom;

import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.schema.meta.PeriodType;

import org.junit.jupiter.api.Test;

import java.time.Period;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code period}: a calendar span, as a signed integer number of months ([TSON-SCHEMA] §5.5).
 *
 * <p>The calendar half of what one duration used to carry. A month has no fixed length, so months and
 * seconds are two value spaces rather than one partially ordered one -- which is what lets both be totally
 * ordered and both carry enforceable bounds.
 */
class PeriodParserTest {

    private static Period read(AtomType<Period> parser, String text) {
        return parser.read(new TokenValue(text, TokenForm.UNQUOTED));
    }

    private static Period read(String text) {
        return read(PeriodParser.UNCONSTRAINED, text);
    }

    private static long months(String text) {
        return read(text).toTotalMonths();
    }

    // ── The grammar ──────────────────────────────────────────────────────

    @Test
    void readsAYearComponentAnMComponentOrBothInThatOrder() {
        assertEquals(12, months("P1Y"));
        assertEquals(18, months("P18M"));
        assertEquals(18, months("P1Y6M"));
    }

    /** {@code P1Y} and {@code P12M} are one value, which is why bounds compare months and not the token. */
    @Test
    void aYearIsTwelveMonthsExactly() {
        assertEquals(months("P1Y"), months("P12M"));
        assertEquals(months("P0Y"), months("P0M"));
        assertEquals(months("P0M"), months("-P0M"));
    }

    @Test
    void theLeadingSignIsAdmitted() {
        assertEquals(-3, months("-P3M"));
        assertEquals(-12, months("-P1Y"));
    }

    /**
     * Everything with a fixed length belongs to {@code duration}. A span that is genuinely both is a record
     * with a field of each, which is the shape that keeps the two orders total.
     */
    @Test
    void theFixedLengthComponentsAreNotAdmitted() {
        assertThrows(AtomParseException.class, () -> read("P1M15D"));
        assertThrows(AtomParseException.class, () -> read("P2W"));
        assertThrows(AtomParseException.class, () -> read("P1YT1H"));
        assertThrows(AtomParseException.class, () -> read("P1Y0D"));
    }

    @Test
    void theOrderIsYearsThenMonths() {
        assertThrows(AtomParseException.class, () -> read("P6M1Y"));
    }

    @Test
    void atLeastOneComponentIsRequired() {
        assertThrows(AtomParseException.class, () -> read("P"));
    }

    @Test
    void noFractionIsAdmitted() {
        assertThrows(AtomParseException.class, () -> read("P1.5Y"));
    }

    // ── The constraints ──────────────────────────────────────────────────

    @Test
    void boundsCompareTheValueAndNotTheToken() {
        AtomType<Period> atLeastAYear = new PeriodParser(Optional.of(Period.ofYears(1)), Optional.empty());

        assertEquals(12, read(atLeastAYear, "P12M").toTotalMonths());
        assertEquals(18, read(atLeastAYear, "P1Y6M").toTotalMonths());
        assertThrows(AtomValidationException.class, () -> read(atLeastAYear, "P11M"));
    }

    @Test
    void multipleOfIgnoresSign() {
        AtomType<Period> quarters = new PeriodParser(new PeriodType(Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(Period.ofMonths(3))));

        assertEquals(6, read(quarters, "P6M").toTotalMonths());
        assertEquals(-6, read(quarters, "-P6M").toTotalMonths());
        assertThrows(AtomValidationException.class, () -> read(quarters, "P4M"));
    }

    // ── Writing ──────────────────────────────────────────────────────────

    @Test
    void writesTheValuesOwnNormalForm() {
        assertEquals("P0M", PeriodParser.UNCONSTRAINED.write(Period.ofMonths(0)));
        assertEquals("P1Y", PeriodParser.UNCONSTRAINED.write(Period.ofMonths(12)));
        assertEquals("P1Y6M", PeriodParser.UNCONSTRAINED.write(Period.ofMonths(18)));
        assertEquals("P6M", PeriodParser.UNCONSTRAINED.write(Period.ofMonths(6)));
        assertEquals("-P3M", PeriodParser.UNCONSTRAINED.write(Period.ofMonths(-3)));
    }

    @Test
    void everySpellingOfOneValueWritesBackTheSameWay() {
        for (String spelling : new String[] {"P1Y", "P12M"}) {
            assertEquals("P1Y", PeriodParser.UNCONSTRAINED.write(read(spelling)), spelling);
        }
    }
}
