package io.ltr8.tson.compiler.atom;

import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import org.junit.jupiter.api.Test;

import io.ltr8.tson.schema.meta.DecimalType;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DecimalParserTest {

    private static TokenValue token(String text) {
        return new TokenValue(text, TokenForm.UNQUOTED);
    }

    private static DecimalParser decimalType(Optional<BigDecimal> min, Optional<BigDecimal> multipleOf,
                                             Optional<Integer> totalDigits, Optional<Integer> fractionDigits) {
        return new DecimalParser(min, Optional.empty(), Optional.empty(), Optional.empty(),
                multipleOf, totalDigits, fractionDigits);
    }

    // ── §7.6 form acceptance (§5.6: "!number accepts integer/float, not the special values") ──

    @Test
    void acceptsPlainIntegers() {
        assertEquals(new BigDecimal("42"), DecimalParser.UNCONSTRAINED.read(token("42")));
    }

    @Test
    void acceptsFloats() {
        assertEquals(new BigDecimal("199.90"), DecimalParser.UNCONSTRAINED.read(token("199.90")));
    }

    @Test
    void preservesExactlyAsWritten() {
        // §5.6: exact tier, "preserved as written" -- 199.90 keeps its trailing zero, unlike a
        // double round-trip which would normalize it away.
        assertEquals("199.90", DecimalParser.UNCONSTRAINED.read(token("199.90")).toString());
    }

    @Test
    void basedIntegerFormIsRejected() {
        // §5.6: number's accepted forms are integer/float only, not based-integer.
        assertThrows(AtomParseException.class, () -> DecimalParser.UNCONSTRAINED.read(token("0xFF")));
    }

    @Test
    void specialValuesAreRejected() {
        // §5.6: "!number, being exact, does not accept the special values."
        assertThrows(AtomParseException.class, () -> DecimalParser.UNCONSTRAINED.read(token(".inf")));
        assertThrows(AtomParseException.class, () -> DecimalParser.UNCONSTRAINED.read(token(".nan")));
    }

    @Test
    void nonNumericTokenIsAParseError() {
        assertThrows(AtomParseException.class, () -> DecimalParser.UNCONSTRAINED.read(token("twelve")));
    }

    // ── Constraint vocabulary ────────────────────────────────────────────────

    @Test
    void minRejectsBelowBound() {
        DecimalParser type = decimalType(Optional.of(new BigDecimal("0")), Optional.empty(), Optional.empty(), Optional.empty());
        assertEquals(new BigDecimal("0"), type.read(token("0")));
        assertThrows(AtomValidationException.class, () -> type.read(token("-0.01")));
    }

    @Test
    void multipleOfRejectsNonMultiples() {
        DecimalParser type = decimalType(Optional.empty(), Optional.of(new BigDecimal("0.05")), Optional.empty(), Optional.empty());
        assertEquals(new BigDecimal("0.10"), type.read(token("0.10")));
        assertThrows(AtomValidationException.class, () -> type.read(token("0.11")));
    }

    @Test
    void totalDigitsRejectsTooManySignificantDigits() {
        DecimalParser type = decimalType(Optional.empty(), Optional.empty(), Optional.of(3), Optional.empty());
        assertEquals(new BigDecimal("123"), type.read(token("123")));
        assertThrows(AtomValidationException.class, () -> type.read(token("1234")));
    }

    @Test
    void fractionDigitsRejectsTooManyDecimalPlaces() {
        DecimalParser type = decimalType(Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(2));
        assertEquals(new BigDecimal("1.23"), type.read(token("1.23")));
        assertThrows(AtomValidationException.class, () -> type.read(token("1.234")));
    }

    // ── the sparse member set (§5.6's `members`) ────────────────────────

    @Test
    void aMemberSetAdmitsItsMembersAndNothingElse() {
        DecimalParser price = members("1", "2.50");

        assertEquals(new BigDecimal("2.50"), price.read(token("2.50")));
        assertThrows(AtomValidationException.class, () -> price.read(token("3")));
        assertThrows(AtomValidationException.class, () -> price.read(token("2.51")));
    }

    @Test
    void membershipIsTheValueDenotedNotTheScale() {
        // BigDecimal's own equality is not [TSON-DATA] §4.3's identity: 2.50 and 2.5 are two objects and
        // one number, so the comparison is compareTo. The value read back still keeps what was written.
        DecimalParser price = members("1", "2.50");
        assertEquals(new BigDecimal("2.5"), price.read(token("2.5")));
        assertEquals(new BigDecimal("1.00"), price.read(token("1.00")));
        assertEquals(new BigDecimal("1E+2"), members("100").read(token("1E+2")));
    }

    @Test
    void aRefusedMemberNamesTheSetAsTheViolatedConstraint() {
        AtomValidationException refused = assertThrows(AtomValidationException.class,
                () -> members("1", "2.50").read(token("3")));
        assertEquals("one of (1, 2.50)", refused.expected());
    }

    @Test
    void aMemberSetComposesWithTheFacetsBesideIt() {
        // §5.6: "a value must satisfy all present facets" -- fraction_digits still refuses 2.50's own scale.
        DecimalParser type = new DecimalParser(new DecimalType(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(1),
                Optional.of(List.of(new BigDecimal("1"), new BigDecimal("2.50")))));
        assertEquals(new BigDecimal("2.5"), type.read(token("2.5")));
        assertThrows(AtomValidationException.class, () -> type.read(token("2.50")));
    }

    private static DecimalParser members(String... admitted) {
        return new DecimalParser(new DecimalType(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(Arrays.stream(admitted).map(BigDecimal::new).toList())));
    }

    // ── read(token, target) ──────────────────────────────────────────────────

    @Test
    void readWithTargetNarrowsToDoubleOrFloat() {
        assertEquals(199.90, DecimalParser.UNCONSTRAINED.read(token("199.90"), double.class));
        assertEquals(199.90f, DecimalParser.UNCONSTRAINED.read(token("199.90"), float.class));
    }

    @Test
    void writeRoundTripsThroughRead() {
        BigDecimal value = DecimalParser.UNCONSTRAINED.read(token("199.90"));
        assertEquals("199.90", DecimalParser.UNCONSTRAINED.write(value));
    }
}
