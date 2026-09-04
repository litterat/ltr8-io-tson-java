package io.ltr8.tson.schema.meta;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [TSON-SCHEMA] §5.7 over the facets whose narrowing rule was unwritten: {@code pattern}, {@code version},
 * and the {@code within}/{@code excluding} pair.
 *
 * <p><b>Four reasons a facet resists the ordinary rules, and these are the last two.</b> {@code
 * bytes_type.encoding} is refused outright because no change ever narrows; {@code complex_type.component}
 * follows a stated partial order. These are the other two: a facet where narrowing is decidable but not
 * cheaply ({@code pattern}, {@code version} — settable where the source left it unset, restatable, never
 * changed), and a pair of ordinary set facets that narrow in <em>opposite directions</em> ({@code within}
 * shrinks, {@code excluding} grows).
 *
 * <p>{@code Ipv4Type}, {@code Ipv6Type} and {@code UuidType} had no {@code constraintsCheck} at all before
 * this, so every facet on them was freely replaceable.
 */
class FacetNarrowingGapsTest {

    private static List<String> refine(Atom source, Atom refined) {
        return source.constraintsCheck(refined);
    }

    private static TextType text(String pattern) {
        return new TextType(Optional.empty(), Optional.empty(), Optional.empty(), Optional.ofNullable(pattern));
    }

    /** Adding a pattern where the source has none narrows — from every string to the ones it matches. */
    @Test
    void aPatternMayBeAddedToAnUnpatternedSource() {
        assertEquals(List.of(), refine(text(null), text("[a-z]+")));
    }

    /** Restating the same one is a no-op and stays permitted. */
    @Test
    void aPatternMayBeRestated() {
        assertEquals(List.of(), refine(text("[a-z]+"), text("[a-z]+")));
    }

    /**
     * Changing one is refused. {@code [a-z]} does contain {@code [a-c]}, so this refusal is conservative
     * rather than exact — it refuses a legal refinement rather than admitting an illegal one, which is the
     * safe direction for a question a schema-load check should not be deciding.
     */
    @Test
    void aPatternMayNotBeChangedEvenWhereItWouldNarrow() {
        List<String> violations = refine(text("[a-z]+"), text("[a-c]+"));
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.getFirst().contains("restated but not changed"), violations.getFirst());
    }

    /** A version names a layout, not a bound: no v7 UUID is a v4 UUID, so a change claims a false IS-A. */
    @Test
    void aUuidVersionIsSettableOnceAndThenFixed() {
        assertEquals(List.of(), refine(new UuidType(Optional.empty()), new UuidType(Optional.of(4))));
        assertEquals(List.of(), refine(new UuidType(Optional.of(4)), new UuidType(Optional.of(4))));
        assertFalse(refine(new UuidType(Optional.of(4)), new UuidType(Optional.of(7))).isEmpty());
    }

    private static Ipv4Type ipv4(List<String> within, List<String> excluding) {
        return new Ipv4Type("", within, excluding);
    }

    /** {@code within} admits only what it names, so a refinement narrows by shrinking it. */
    @Test
    void withinMayShrinkAndMayNotGrow() {
        assertEquals(List.of(), refine(ipv4(List.of("10.0.0.0/8", "192.168.0.0/16"), List.of()),
                ipv4(List.of("10.0.0.0/8"), List.of())));
        assertFalse(refine(ipv4(List.of("10.0.0.0/8"), List.of()),
                ipv4(List.of("10.0.0.0/8", "192.168.0.0/16"), List.of())).isEmpty());
    }

    /** {@code excluding} removes values, so it narrows the other way: it may grow and may not shrink. */
    @Test
    void excludingMayGrowAndMayNotShrink() {
        assertEquals(List.of(), refine(ipv4(List.of(), List.of("10.0.0.0/8")),
                ipv4(List.of(), List.of("10.0.0.0/8", "127.0.0.0/8"))));
        List<String> violations = refine(ipv4(List.of(), List.of("10.0.0.0/8", "127.0.0.0/8")),
                ipv4(List.of(), List.of("10.0.0.0/8")));
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.getFirst().contains("may grow but never shrink"), violations.getFirst());
    }

    /** And the same pair on the CIDR families, where {@code within} was already checked and this was not. */
    @Test
    void theCidrFamiliesGetTheSameExcludingRule() {
        Cidr4Type source = new Cidr4Type("", Optional.empty(), Optional.empty(),
                List.of(), List.of("10.0.0.0/8"));
        Cidr4Type dropped = new Cidr4Type("", Optional.empty(), Optional.empty(), List.of(), List.of());
        assertFalse(refine(source, dropped).isEmpty());
    }
}
