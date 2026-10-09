package io.ltr8.unicode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RFC 5893's six conditions, one label at a time. Characters beyond ASCII are escapes. */
class BidiRuleTest {

    /** {@code مثال}, Arabic for "example". */
    private static final String ARABIC = "مثال";

    /** {@code שלום}, Hebrew. */
    private static final String HEBREW = "שלום";

    @Test
    void rightToLeftTextMakesABidiName() {
        assertTrue(BidiRule.hasRightToLeft(ARABIC));
        assertTrue(BidiRule.hasRightToLeft("a١"));
        assertFalse(BidiRule.hasRightToLeft("example"));
    }

    @Test
    void wellFormedLabelsPass() {
        assertTrue(BidiRule.violation(ARABIC).isEmpty());
        assertTrue(BidiRule.violation(HEBREW + "1").isEmpty());
        assertTrue(BidiRule.violation("example").isEmpty());
        assertTrue(BidiRule.violation("abc1").isEmpty());
    }

    @Test
    void aLabelMustBeginInOneDirection() {
        assertTrue(BidiRule.violation("1abc").orElseThrow().contains("rule 1"));
    }

    @Test
    void aRightToLeftLabelHoldsNoLeftToRightCharacter() {
        assertTrue(BidiRule.violation(HEBREW + "a").orElseThrow().contains("rule 2"));
    }

    @Test
    void aRightToLeftLabelEndsRightToLeftOrInADigit() {
        assertTrue(BidiRule.violation(HEBREW + "-").orElseThrow().contains("rule 3"));
    }

    @Test
    void aRightToLeftLabelMixesNoDigitSystems() {
        assertEquals(true, BidiRule.violation(ARABIC + "1١").orElseThrow().contains("rule 4"));
    }

    @Test
    void aLeftToRightLabelHoldsNoRightToLeftCharacter() {
        assertTrue(BidiRule.violation("a" + HEBREW).orElseThrow().contains("rule 5"));
    }

    @Test
    void aLeftToRightLabelEndsLeftToRightOrInADigit() {
        assertTrue(BidiRule.violation("abc-").orElseThrow().contains("rule 6"));
    }
}
