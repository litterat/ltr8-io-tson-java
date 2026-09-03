package io.ltr8.tson.compiler.atom;

import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.schema.meta.BytesType;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.meta.FloatType;
import io.ltr8.tson.schema.meta.IntegerSize;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link AtomTypeException#expected()} across every shape its own Javadoc admits -- an ordering bound, a
 * membership, a length, a pattern, a grammar, a prohibition. These are pinned here rather than left to each
 * parser's own test because the value of the field is that it is *one* vocabulary: a consumer that learns
 * {@code <= 100} from an integer must read the same thing off a decimal, and a site inventing a seventh
 * phrase is the failure mode, not a wrong bound at one site.
 *
 * <p>The paired assertion on {@code getMessage()} is the point of the split: the sentence is for a person,
 * {@code expected()} is the constraint standing alone, and neither is the other's prefix.
 */
class AtomTypeExceptionTest {

    private static TokenValue token(String text) {
        return new TokenValue(text, TokenForm.UNQUOTED);
    }

    private static AtomTypeException rejecting(AtomType<?> type, String text) {
        return assertThrows(AtomTypeException.class, () -> type.read(token(text)));
    }

    // ── An ordering bound ────────────────────────────────────────────────

    @Test
    void anInclusiveBoundIsAComparison() {
        assertEquals("<= 100", rejecting(IntegerParser.ofMax(BigInteger.valueOf(100)), "101").expected());
        assertEquals(">= 1", rejecting(IntegerParser.ofMin(BigInteger.ONE), "0").expected());
    }

    /** A width-bounded integer states both ends at once -- one constraint, not two the caller must pair up. */
    @Test
    void anIntegerLadderWidthIsATwoSidedRange() {
        assertEquals(">= -128 and <= 127", rejecting(new IntegerParser(new IntegerSize(8, true)), "128").expected());
        assertEquals(">= 0 and <= 255", rejecting(new IntegerParser(new IntegerSize(8, false)), "-1").expected());
    }

    /** The same phrases off a different numeric atom -- the vocabulary is the exception's, not each parser's. */
    @Test
    void aDecimalStatesItsBoundTheSameWayAnIntegerDoes() {
        DecimalParser type = new DecimalParser(Optional.empty(), Optional.empty(),
                Optional.of(new BigDecimal("100")), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
        AtomTypeException rejection = rejecting(type, "100.5");

        assertEquals("<= 100", rejection.expected());
        assertEquals("'100.5' is greater than the maximum 100", rejection.getMessage());
    }

    // ── A membership ─────────────────────────────────────────────────────

    @Test
    void anEnumListsItsMembers() {
        AtomTypeException rejection =
                rejecting(new EnumParser(new EnumBody(List.of("PENDING", "SHIPPED", "DELIVERED"))), "CANCELLED");

        assertEquals("one of (PENDING, SHIPPED, DELIVERED)", rejection.expected());
        assertEquals("'CANCELLED' is not a member of this enum -- expected one of [PENDING, SHIPPED, DELIVERED]",
                rejection.getMessage());
    }

    // ── A length ─────────────────────────────────────────────────────────

    @Test
    void aLengthFacetCountsCharacters() {
        TextParser exact = new TextParser(Optional.empty(), Optional.empty(), Optional.of(5), Optional.empty());
        TextParser atLeast = new TextParser(Optional.of(3), Optional.empty(), Optional.empty(), Optional.empty());
        TextParser atMost = new TextParser(Optional.empty(), Optional.of(3), Optional.empty(), Optional.empty());

        assertEquals("exactly 5 characters", rejecting(exact, "hi").expected());
        assertEquals("at least 3 characters", rejecting(atLeast, "ab").expected());
        assertEquals("at most 3 characters", rejecting(atMost, "abcd").expected());
    }

    /** A binary atom's length is bytes, and says so -- the decoded length, not the encoded token's. */
    @Test
    void aBinaryLengthCountsBytes() {
        BytesParser type = new BytesParser(BytesParser.Encoding.HEX, Optional.of(4), Optional.empty());
        assertEquals("at least 4 bytes", rejecting(type, "aabb").expected());
    }

    // ── A pattern ────────────────────────────────────────────────────────

    /**
     * The pattern verbatim, unquoted and unescaped: it is already the schema's own text, and a consumer
     * feeding it back to a regex engine must not have to strip a layer of this library's own quoting.
     */
    @Test
    void aPatternIsCarriedVerbatim() {
        TextParser type = new TextParser(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of("[A-Z]{3}"));
        assertEquals("matching [A-Z]{3}", rejecting(type, "abc").expected());
    }

    // ── A grammar (the parse half) ───────────────────────────────────────

    /**
     * A parse failure is the one case where {@code expected} names a shape rather than a facet, because a
     * shape is exactly what was expected -- there is no bound to state about a token that never became a
     * value.
     */
    @Test
    void aParseFailureNamesTheProductionTheTokenHadToSatisfy() {
        assertEquals("an RFC 3339 full-date", rejecting(DateParser.UNCONSTRAINED, "not-a-date").expected());
        assertEquals("an integer or based-integer form", rejecting(IntegerParser.UNCONSTRAINED, "twelve").expected());
        assertEquals("a UUID", rejecting(UuidParser.UNCONSTRAINED, "nope").expected());
        assertEquals("an EUI-48 MAC address", rejecting(MacParser.UNCONSTRAINED, "nope").expected());
    }

    // ── A prohibition ────────────────────────────────────────────────────

    /** {@code allow_*}'s facet is a boolean, so the constraint it violates is a negation, not a bound. */
    @Test
    void aDisallowedSpecialValueIsANegation() {
        FloatParser noNan = float64(false, true);
        FloatParser noInfinity = float64(true, false);

        assertEquals("not NaN", rejecting(noNan, ".nan").expected());
        assertEquals("a finite value", rejecting(noInfinity, ".inf").expected());
    }

    private static FloatParser float64(boolean allowNan, boolean allowInfinity) {
        return new FloatParser(FloatType.Format.BINARY64, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), allowNan, allowInfinity, true, true);
    }
}
