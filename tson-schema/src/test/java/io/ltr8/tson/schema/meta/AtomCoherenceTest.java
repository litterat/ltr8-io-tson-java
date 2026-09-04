package io.ltr8.tson.schema.meta;

import io.ltr8.tson.schema.atom.Rational;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.util.List;
import java.time.Duration;
import java.time.Period;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Atom#coherenceCheck} per family -- does a single declared body's own facets contradict each
 * other? The {@code constraintsCheck} tests in {@code DefinitionResolverTest} ask the other question
 * (does this refinement tighten its source?); these need no source, no resolver and no schema, so
 * they pin each family's rule directly rather than through the pipeline that calls it. {@code
 * DefinitionResolverTest} covers the wiring.
 *
 * <p>Every test asserts the coherent case too. A check that fires on a valid body is worse than one
 * that never fires, since it rejects schemas that were always fine -- so "which shapes must NOT be
 * reported" is pinned alongside "which must".
 */
class AtomCoherenceTest {

    private static Optional<Integer> some(int value) {
        return Optional.of(value);
    }

    private static final Optional<Integer> NONE = Optional.empty();

    private static void assertCoherent(Atom atom) {
        assertEquals(List.of(), atom.coherenceCheck(), atom + " should be coherent");
    }

    private static void assertViolation(Atom atom, String fragment) {
        List<String> violations = atom.coherenceCheck();
        assertTrue(violations.stream().anyMatch(v -> v.contains(fragment)),
                () -> "expected a violation containing '" + fragment + "', got " + violations);
    }

    // ── Nothing stated contradicts nothing ───────────────────────────────────

    /**
     * The property that keeps this check silent on every real schema: an incoherence needs two
     * present facets, so every unconstrained instance in the package -- the bodies core.tn's own
     * atoms all resolve to -- is coherent by construction.
     */
    @Test
    void everyUnconstrainedInstanceIsCoherent() {
        assertCoherent(IntegerType.UNCONSTRAINED);
        assertCoherent(TextType.UNCONSTRAINED);
        assertCoherent(UriType.UNCONSTRAINED);
        assertCoherent(RegexType.UNCONSTRAINED);
        assertCoherent(EmailType.UNCONSTRAINED);
        assertCoherent(DecimalType.UNCONSTRAINED);
        assertCoherent(RationalType.UNCONSTRAINED);
        assertCoherent(DateType.UNCONSTRAINED);
        assertCoherent(TimeType.UNCONSTRAINED);
        assertCoherent(DateTimeType.UNCONSTRAINED);
        assertCoherent(DurationType.UNCONSTRAINED);
        assertCoherent(Cidr4Type.UNCONSTRAINED);
        assertCoherent(Cidr6Type.UNCONSTRAINED);
        assertCoherent(Ipv4Type.UNCONSTRAINED);
        assertCoherent(Ipv6Type.UNCONSTRAINED);
        assertCoherent(MacType.UNCONSTRAINED);
        assertCoherent(UuidType.UNCONSTRAINED);
        assertCoherent(ComplexType.UNCONSTRAINED);
        assertCoherent(new Unit());
    }

    /** One end alone is a half-open range, which is the normal way to write a floor or a ceiling. */
    @Test
    void oneBoundAloneIsNeverIncoherent() {
        assertCoherent(IntegerType.ofMin(BigInteger.ONE));
        assertCoherent(IntegerType.ofMax(BigInteger.valueOf(-1)));
        assertCoherent(new TextType(some(1), NONE, NONE, Optional.empty()));
        assertCoherent(new Cidr4Type("s", some(8), NONE, List.of(), List.of()));
    }

    // ── Length families ──────────────────────────────────────────────────────

    @Test
    void textRejectsAFloorAboveItsCeiling() {
        assertViolation(new TextType(some(10), some(3), NONE, Optional.empty()),
                "min_length 10 is above max_length 3");
        assertCoherent(new TextType(some(3), some(10), NONE, Optional.empty()));
    }

    /**
     * The case a pairwise floor-vs-ceiling check alone would miss: neither stated facet contradicts
     * the other, but the exact {@code length} between them falls outside what they leave.
     */
    @Test
    void textRejectsAnExactLengthOutsideItsOwnBounds() {
        assertViolation(new TextType(NONE, some(3), some(5), Optional.empty()), "length 5 is above max_length 3");
        assertViolation(new TextType(some(7), NONE, some(5), Optional.empty()), "min_length 7 is above length 5");
        assertCoherent(new TextType(some(1), some(10), some(5), Optional.empty()));
    }

    /** A range admitting exactly one length is a legitimate way to pin a fixed-width text, not an incoherence. */
    @Test
    void textAcceptsARangePinningASingleLength() {
        assertCoherent(new TextType(some(5), some(5), some(5), Optional.empty()));
    }

    @Test
    void everyLengthFacetMustBeNonNegative() {
        assertViolation(new TextType(some(-1), NONE, NONE, Optional.empty()), "min_length -1 is negative");
        assertViolation(new TextType(NONE, some(-1), NONE, Optional.empty()), "max_length -1 is negative");
        assertViolation(new TextType(NONE, NONE, some(-1), Optional.empty()), "length -1 is negative");
    }

    /** The three families composing {@code text_type}'s facets delegate rather than restating the rule. */
    @Test
    void theTextComposingFamiliesInheritTheSameLengthRule() {
        assertViolation(new UriType("s", some(10), some(3), NONE, Optional.empty(), Optional.empty()),
                "min_length 10 is above max_length 3");
        assertViolation(new RegexType("s", some(10), some(3), NONE, Optional.empty()),
                "min_length 10 is above max_length 3");
        assertViolation(new EmailType("s", some(10), some(3), NONE, Optional.empty()),
                "min_length 10 is above max_length 3");
    }

    /** Binary carries the same two length facets, minus {@code length}, and counts decoded bytes rather than code points. */
    @Test
    void binaryRejectsAFloorAboveItsCeiling() {
        assertViolation(new BytesType(BytesType.Encoding.BASE64, java.util.Optional.empty(), some(10), some(3)),
                "min_length 10 is above max_length 3");
        assertCoherent(new BytesType(BytesType.Encoding.BASE64, java.util.Optional.empty(), some(3), some(10)));
    }

    // ── Range families ───────────────────────────────────────────────────────

    private static IntegerType integerRange(Long min, Long exclusiveMin, Long max, Long exclusiveMax) {
        return new IntegerType(Optional.empty(), Optional.ofNullable(min).map(BigInteger::valueOf),
                Optional.ofNullable(exclusiveMin).map(BigInteger::valueOf),
                Optional.ofNullable(max).map(BigInteger::valueOf),
                Optional.ofNullable(exclusiveMax).map(BigInteger::valueOf), Optional.empty());
    }

    @Test
    void integerRejectsAFloorAboveItsCeiling() {
        assertViolation(integerRange(10L, null, 3L, null), "min 10 is above max 3");
        assertCoherent(integerRange(3L, null, 10L, null));
    }

    /**
     * Emptiness, not narrowness: bounds meeting at one value pin a constant and pass, while the same
     * meeting point with either end exclusive admits nothing.
     */
    @Test
    void integerDistinguishesAPinnedValueFromAnExcludedOne() {
        assertCoherent(integerRange(5L, null, 5L, null));
        assertViolation(integerRange(null, 5L, 5L, null), "meet at 5");
        assertViolation(integerRange(5L, null, null, 5L), "meet at 5");
    }

    /**
     * The integer family's own wrinkle: a bound can contradict the range its {@code size} implies
     * rather than another stated bound, so this is the one family where a single stated facet is
     * enough to be incoherent.
     */
    @Test
    void integerRejectsABoundOutsideTheRangeItsWidthImplies() {
        IntegerType belowAnUnsignedFloor = new IntegerType(Optional.of(new IntegerSize(BigInteger.valueOf(8), false)),
                Optional.empty(), Optional.empty(), Optional.of(BigInteger.valueOf(-5)), Optional.empty(),
                Optional.empty());
        assertViolation(belowAnUnsignedFloor, "is above max -5");

        IntegerType aboveAnUnsignedCeiling = new IntegerType(Optional.of(new IntegerSize(BigInteger.valueOf(8), false)),
                Optional.of(BigInteger.valueOf(300)), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
        assertViolation(aboveAnUnsignedCeiling, "min 300 is above");

        IntegerType inside = new IntegerType(Optional.of(new IntegerSize(BigInteger.valueOf(8), false)),
                Optional.of(BigInteger.ZERO), Optional.empty(), Optional.of(BigInteger.valueOf(255)),
                Optional.empty(), Optional.empty());
        assertCoherent(inside);
    }

    /**
     * {@code multiple_of: 0} is the one incoherence here that is actively unsound rather than merely
     * undiagnosed: {@code IntegerParser}/{@code DecimalParser} validate with {@code
     * value.remainder(m)}, which throws on a zero divisor, so leaving it unchecked turns a read of a
     * perfectly valid document into a library-fault report.
     */
    @Test
    void aStepMustBeAUsableDivisor() {
        IntegerType zeroStep = new IntegerType(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(BigInteger.ZERO));
        assertViolation(zeroStep, "multiple_of is zero, which divides nothing");

        IntegerType negativeStep = new IntegerType(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(BigInteger.valueOf(-2)));
        assertViolation(negativeStep, "multiple_of -2 is negative");

        IntegerType usable = new IntegerType(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(BigInteger.TWO));
        assertCoherent(usable);
    }

    @Test
    void decimalRejectsAnEmptyRangeAndAnUnusableStep() {
        assertViolation(new DecimalType(Optional.of(BigDecimal.TEN), Optional.empty(),
                Optional.of(BigDecimal.ONE), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                "min 10 is above max 1");
        assertViolation(new DecimalType(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(BigDecimal.ZERO), Optional.empty(), Optional.empty()), "divides nothing");
    }

    /**
     * SQL's own {@code DECIMAL(precision, scale)} rule, which meta.tn names this pair after: the
     * scale fits inside the precision.
     */
    @Test
    void decimalRejectsAScaleAboveItsPrecision() {
        assertViolation(new DecimalType(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), some(2), some(5)), "fraction_digits 5 is above total_digits 2");
        assertCoherent(new DecimalType(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), some(5), some(2)));
    }

    /** Bounds compare by cross-multiplication, so the contradiction is caught however either end is written. */
    @Test
    void rationalJudgesBoundsByValueNotBySpelling() {
        Rational threeQuarters = new Rational(BigInteger.valueOf(6), BigInteger.valueOf(8));
        Rational aHalf = new Rational(BigInteger.valueOf(2), BigInteger.valueOf(4));
        assertViolation(new RationalType(Optional.of(threeQuarters), Optional.empty(), Optional.of(aHalf),
                Optional.empty(), Optional.empty()), "is above max");
        assertCoherent(new RationalType(Optional.of(aHalf), Optional.empty(), Optional.of(threeQuarters),
                Optional.empty(), Optional.empty()));
    }

    @Test
    void floatRejectsAnEmptyRange() {
        assertViolation(new FloatType(FloatType.Format.BINARY64, Optional.of(BigDecimal.TEN), Optional.empty(),
                Optional.of(BigDecimal.ONE), Optional.empty(), true, true, true, true), "min 10 is above max 1");
    }

    /**
     * The {@code allow_*} flags are independent permissions, not a range: withdrawing all four still
     * leaves every ordinary finite value.
     */
    @Test
    void floatWithdrawingEveryPermissionIsCoherent() {
        assertCoherent(new FloatType(FloatType.Format.BINARY64, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), false, false, false, false));
    }

    // ── Temporal families ────────────────────────────────────────────────────

    /**
     * Reached by direct construction rather than through a resolved schema, and that is not merely a
     * unit-test convenience: no temporal bound can be written in schema text today. {@code
     * date_type.min}/{@code max} are declared {@code value?} in meta.tn, so a bound binds as a {@code
     * String} into an {@code Optional<LocalDate>} and throws before any coherence check runs -- a
     * separate, pre-existing defect. These rules go live at the resolver when that is fixed.
     */
    @Test
    void temporalFamiliesRejectACeilingBelowTheirFloor() {
        assertViolation(new DateType(Optional.of(LocalDate.of(2030, 1, 1)), Optional.of(LocalDate.of(2020, 1, 1))),
                "min 2030-01-01 is above max 2020-01-01");
        assertCoherent(new DateType(Optional.of(LocalDate.of(2020, 1, 1)), Optional.of(LocalDate.of(2030, 1, 1))));

        assertViolation(new TimeType(Optional.of(OffsetTime.of(18, 0, 0, 0, ZoneOffset.UTC)),
                Optional.of(OffsetTime.of(9, 0, 0, 0, ZoneOffset.UTC))), "is above max");

        assertViolation(new DateTimeType(
                Optional.of(OffsetDateTime.of(2030, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)),
                Optional.of(OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC))), "is above max");
    }

    /**
     * Bounds written in two different offsets are comparable rather than incomparable -- {@code
     * OffsetTime} normalises to the instant first -- so the check judges them instead of waving them
     * through. Both pairs read backwards on their local times alone, and the offsets are what decide
     * them: 09:00+02:00 is 07:00 UTC, genuinely before the 08:00 UTC ceiling, while 08:00+02:00 is
     * 06:00 UTC, genuinely after the 09:00 UTC floor.
     */
    @Test
    void timeBoundsInDifferentOffsetsAreStillJudged() {
        assertCoherent(new TimeType(Optional.of(OffsetTime.of(9, 0, 0, 0, ZoneOffset.ofHours(2))),
                Optional.of(OffsetTime.of(8, 0, 0, 0, ZoneOffset.UTC))));
        assertViolation(new TimeType(Optional.of(OffsetTime.of(9, 0, 0, 0, ZoneOffset.UTC)),
                Optional.of(OffsetTime.of(8, 0, 0, 0, ZoneOffset.ofHours(2)))), "is above max");
    }

    /**
     * The family that used to be the one exception. Its bounds went unjudged while a duration could carry
     * calendar components: {@code P1M} against {@code P30D} has no answer, a month having no fixed length,
     * so the pair could not be ordered and the facets it declared were decoration. Splitting the calendar
     * half into {@code period} left every duration a fixed number of seconds, and the ordinary range check
     * applies to both.
     */
    @Test
    void durationBoundsAreJudgedNowThatTheValueSpaceIsOrdered() {
        assertCoherent(new DurationType(Optional.of(Duration.ofDays(1)), Optional.of(Duration.ofDays(30))));
        assertViolation(new DurationType(Optional.of(Duration.ofDays(30)), Optional.of(Duration.ofDays(1))),
                "is above");
    }

    /** And the calendar half, on the months it denotes -- {@code P1Y} is above {@code P6M}. */
    @Test
    void periodBoundsAreJudgedOnMonths() {
        assertCoherent(new PeriodType(Optional.of(Period.ofMonths(6)), Optional.of(Period.ofYears(1))));
        assertViolation(new PeriodType(Optional.of(Period.ofYears(1)), Optional.of(Period.ofMonths(6))),
                "is above");
    }

    // ── CIDR families ────────────────────────────────────────────────────────

    /**
     * meta.tn's own {@code @doc} states this rule outright -- prefix bounds narrow "within the family
     * range 0-32" and "bounds outside that range are invalid at the schema level" -- so {@code 40} is
     * reported twice over: outside the family's own width, and above its own ceiling.
     */
    @Test
    void cidr4RejectsAPrefixOutsideTheFamilyRangeAndOutOfOrder() {
        Cidr4Type both = new Cidr4Type("s", some(40), some(8), List.of(), List.of());
        assertViolation(both, "min_prefix 40 is outside the family range 0-32");
        assertViolation(both, "min_prefix 40 is above max_prefix 8");
        assertViolation(new Cidr4Type("s", NONE, some(-1), List.of(), List.of()), "outside the family range 0-32");
        assertCoherent(new Cidr4Type("s", some(8), some(32), List.of(), List.of()));
    }

    /** The IPv6 twin, over the 128 bits its own {@code @doc} names -- where a /40 is perfectly ordinary. */
    @Test
    void cidr6UsesItsOwnWiderFamilyRange() {
        assertCoherent(new Cidr6Type("s", some(40), some(64), List.of(), List.of()));
        assertViolation(new Cidr6Type("s", NONE, some(129), List.of(), List.of()),
                "max_prefix 129 is outside the family range 0-128");
    }

    // ── Families with nothing orderable to contradict ─────────────────────────

    /**
     * A selector picks among unordered alternatives, so no two selections contradict -- the same
     * reason {@link ComplexType} records for leaving them out of the narrowing check. These families
     * keep the permissive default, and a facet the wire can carry is never enough on its own.
     */
    @Test
    void selectorOnlyFamiliesHaveNothingToContradict() {
        assertCoherent(new UuidType(some(4)));
        assertCoherent(new ComplexType(ComplexType.Component.FLOAT64));
        assertCoherent(new MacType("s"));
        assertCoherent(new Ipv4Type("s", List.of("10.0.0.0/8"), List.of("10.1.0.0/16")));
        assertCoherent(new EnumBody(List.of("A", "B")));
    }

    // ── within/excluding must leave a value between them (§5.5) ──────────────

    /**
     * The pair an author reaches by editing rather than by trying: a {@code within} entry and the identical
     * {@code excluding} entry. {@code { min: 10 max: 3 }}'s exact twin, and the reason this rule is worth
     * having where the {@code pattern}-emptiness one was not -- that one needed {@code [^\p{L}\P{L}]}.
     */
    @Test
    void anExclusionCoveringItsOwnWithinEntryAdmitsNothing() {
        assertViolation(new Ipv4Type("s", List.of("10.0.0.0/8"), List.of("10.0.0.0/8")),
                "excluding covers every network within permits");
        assertViolation(new Cidr4Type("s", NONE, NONE, List.of("10.0.0.0/8"), List.of("10.0.0.0/8")),
                "excluding covers every network within permits");
    }

    /** A wider exclusion covers it too -- containment, not equality, is what empties the pair. */
    @Test
    void anExclusionWiderThanItsWithinEntryAdmitsNothing() {
        assertViolation(new Ipv4Type("s", List.of("10.1.0.0/16"), List.of("10.0.0.0/8")),
                "excluding covers every network within permits");
    }

    /**
     * <b>Partial cover is the case the arithmetic exists for.</b> Neither exclusion covers the {@code within}
     * entry, and between them they tile it exactly -- which is a counting question over a prefix tree, not a
     * search: two halves of a /8 are two /9s, and their sizes sum to it.
     */
    @Test
    void exclusionsThatTileAWithinEntryBetweenThemAdmitNothing() {
        assertViolation(new Ipv4Type("s", List.of("10.0.0.0/8"), List.of("10.0.0.0/9", "10.128.0.0/9")),
                "excluding covers every network within permits");
    }

    /** One tile short, and the type is inhabited again -- the rule is cover, not "several exclusions". */
    @Test
    void exclusionsThatLeaveOneTileUncoveredAreCoherent() {
        assertCoherent(new Ipv4Type("s", List.of("10.0.0.0/8"), List.of("10.0.0.0/9")));
        // A /10, a /10 and a /9 do tile a /8, so this pair leaves exactly the third quarter and no more.
        assertCoherent(new Ipv4Type("s", List.of("10.0.0.0/8"), List.of("10.0.0.0/10", "10.128.0.0/9")));
    }

    /** Cover has to be reached in every {@code within} entry: one uncovered entry inhabits the type. */
    @Test
    void aSecondWithinEntryTheExclusionsDoNotReachIsCoherent() {
        assertCoherent(new Ipv4Type("s", List.of("10.0.0.0/8", "192.168.0.0/16"), List.of("10.0.0.0/8")));
    }

    /** No {@code within} is the whole address space, so excluding it wholesale still empties the body. */
    @Test
    void excludingTheWholeSpaceWithNoWithinAdmitsNothing() {
        assertViolation(new Ipv4Type("s", List.of(), List.of("0.0.0.0/0")),
                "excluding covers every network within permits");
        assertCoherent(new Ipv4Type("s", List.of(), List.of("10.0.0.0/8")));
    }

    /** The same walk over sixteen octets. */
    @Test
    void theV6FamiliesUseTheSameArithmetic() {
        assertViolation(new Ipv6Type("s", List.of("2001:db8::/32"), List.of("2001:db8::/32")),
                "excluding covers every network within permits");
        assertCoherent(new Ipv6Type("s", List.of("2001:db8::/32"), List.of("2001:db8:1::/48")));
    }

    /**
     * <b>The prefix bounds are folded in, and this is why.</b> A network family's value is a block, refused
     * for <em>overlapping</em> an exclusion rather than only for being covered by one. Every address here
     * survives but one, and the only block short enough for {@code max_prefix} is the {@code within} entry
     * itself, which contains that one -- so the body admits no network while admitting almost every address.
     * The message names the largest survivor ({@code 10.0.0.128/25}) rather than the rule, since that is the
     * number an author moves {@code max_prefix} to.
     */
    @Test
    void aSurvivingBlockTooSmallForMaxPrefixAdmitsNoNetwork() {
        assertViolation(new Cidr4Type("s", NONE, some(24), List.of("10.0.0.0/24"), List.of("10.0.0.5/32")),
                "the largest block they leave is a /25, and max_prefix is 24");
        // Lift the ceiling to the surviving block's own length and the same body is inhabited.
        assertCoherent(new Cidr4Type("s", NONE, some(32), List.of("10.0.0.0/24"), List.of("10.0.0.5/32")));
    }

    /** And the address family is not troubled by it: an uncovered address is a value there. */
    @Test
    void theAddressFamilyAdmitsWhatTheNetworkFamilyRefuses() {
        assertCoherent(new Ipv4Type("s", List.of("10.0.0.0/24"), List.of("10.0.0.5/32")));
    }

    /**
     * A malformed entry is {@code checkNetworks}' to report, and reporting the emptiness it causes on top
     * would name a consequence as a second cause. Same for an inverted prefix pair.
     */
    @Test
    void anUnreadableFacetIsReportedOnceByTheCheckThatOwnsIt() {
        List<String> violations = new Ipv4Type("s", List.of("nonsense"), List.of("0.0.0.0/0")).coherenceCheck();
        assertEquals(1, violations.size(), () -> "one cause, one message: " + violations);
        assertTrue(violations.getFirst().contains("not an IPv4 network"), violations::toString);

        List<String> inverted =
                new Cidr4Type("s", some(24), some(8), List.of("10.0.0.0/8"), List.of("10.0.0.0/8")).coherenceCheck();
        assertEquals(1, inverted.size(), () -> "one cause, one message: " + inverted);
    }
}
