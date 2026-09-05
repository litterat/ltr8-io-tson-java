package io.ltr8.tson.schema.meta;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [TSON-SCHEMA] §5.7: a refinement may move {@code complex_type.component} only to a member whose value set
 * is a subset of the source's.
 *
 * <p>The five members are a partial order rather than a chain, which is why neither of §5.7's simpler answers
 * fits. {@code INTEGER ⊂ NUMBER ⊂ RATIONAL} are the exact tiers and {@code FLOAT32 ⊂ FLOAT64} the approximate
 * ones; the two families are incomparable, binary64 carrying ±inf and NaN that no exact decimal represents.
 *
 * <p>core.tn's own {@code @doc} shows both spellings side by side — {@code ^ { component: INTEGER }} for
 * Gaussian integers and {@code ^ { component: FLOAT64 }} for floating-point complex — and only the first is a
 * refinement. The second claims an IS-A that does not hold, which is the defect the four sibling binary types
 * were removed for, reached by a different route.
 */
class ComplexComponentNarrowingTest {

    private static final ComplexType.Component[] EXACT = {
            ComplexType.Component.INTEGER, ComplexType.Component.NUMBER, ComplexType.Component.RATIONAL };

    private static java.util.List<String> refine(ComplexType.Component source, ComplexType.Component refined) {
        return new ComplexType(source).constraintsCheck(new ComplexType(refined));
    }

    /** Down a chain is a real narrowing: Gaussian integers sit inside the exact-decimal complexes. */
    @Test
    void narrowingWithinTheExactTiersIsPermitted() {
        assertEquals(java.util.List.of(), refine(ComplexType.Component.NUMBER, ComplexType.Component.INTEGER));
        assertEquals(java.util.List.of(), refine(ComplexType.Component.RATIONAL, ComplexType.Component.NUMBER));
        assertEquals(java.util.List.of(), refine(ComplexType.Component.RATIONAL, ComplexType.Component.INTEGER));
    }

    /** And within the approximate ones: every binary32 is exactly a binary64. */
    @Test
    void narrowingWithinTheApproximateTiersIsPermitted() {
        assertEquals(java.util.List.of(), refine(ComplexType.Component.FLOAT64, ComplexType.Component.FLOAT32));
    }

    /** Restating the source's own component is a no-op and stays permitted. */
    @Test
    void restatingTheSameComponentIsPermitted() {
        for (ComplexType.Component c : ComplexType.Component.values()) {
            assertEquals(java.util.List.of(), refine(c, c), c.name());
        }
    }

    /** Up a chain is a widening, and refused as one. */
    @Test
    void wideningIsRefused() {
        assertFalse(refine(ComplexType.Component.INTEGER, ComplexType.Component.NUMBER).isEmpty());
        assertFalse(refine(ComplexType.Component.FLOAT32, ComplexType.Component.FLOAT64).isEmpty());
    }

    /**
     * The case core.tn documents and this refuses: neither family contains the other, so the IS-A a
     * refinement claims is false in both directions.
     */
    @Test
    void crossingTheExactAndApproximateFamiliesIsRefused() {
        for (ComplexType.Component exact : EXACT) {
            assertFalse(refine(exact, ComplexType.Component.FLOAT64).isEmpty(), exact.name() + " -> FLOAT64");
            assertFalse(refine(ComplexType.Component.FLOAT64, exact).isEmpty(), "FLOAT64 -> " + exact.name());
        }
    }

    /** And the message names the remedy, since refusing without one leaves the author no route. */
    @Test
    void theRefusalNamesTheTypeToDeclareInstead() {
        String violation = refine(ComplexType.Component.NUMBER, ComplexType.Component.FLOAT64).getFirst();
        assertTrue(violation.contains("!complex_type { component: FLOAT64 }"), violation);
    }
}
