package io.ltr8.tson.regex;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TsonRegex#isDisjointFrom}: does any string match both patterns? Exact (regular-language
 * intersection emptiness), so every case has a definite answer -- the building block for §5.4 pattern
 * disjointness over {@code regex}-constrained choice variants.
 */
class TsonRegexDisjointnessTest {

    private static boolean disjoint(String a, String b) {
        boolean result = TsonRegex.parse(a).isDisjointFrom(TsonRegex.parse(b));
        // disjointness is symmetric -- verify both directions agree
        assertTrue(result == TsonRegex.parse(b).isDisjointFrom(TsonRegex.parse(a)),
                () -> "disjoint(" + a + "," + b + ") is not symmetric");
        return result;
    }

    @Test
    void disjointPatterns() {
        assertTrue(disjoint("[a-z]+", "[0-9]+"));
        assertTrue(disjoint("abc", "abd"));
        assertTrue(disjoint("a+", "b+"));          // every a+ string starts with 'a', every b+ with 'b'
        assertTrue(disjoint("[a-m]", "[n-z]"));
        assertTrue(disjoint("cat", "dog"));
        assertTrue(disjoint("\\p{Lu}", "\\p{Ll}")); // uppercase vs lowercase letters
        assertTrue(disjoint("\\p{Nd}", "\\p{L}"));  // digits vs letters
        assertTrue(disjoint("foo|bar", "baz"));
        assertTrue(disjoint("a{3}", "a{4}"));       // exactly three vs exactly four
    }

    @Test
    void overlappingPatterns() {
        assertFalse(disjoint("a*", "b*"));          // both match the empty string
        assertFalse(disjoint("\\p{Nd}", "[0-9]"));  // ASCII digits are also category Nd
        assertFalse(disjoint("[a-c]", "[b-d]"));    // 'b' and 'c' are common
        assertFalse(disjoint("abc", "ab."));        // "abc" matches both ('.' matches 'c')
        assertFalse(disjoint(".", "a"));            // '.' matches "a"
        assertFalse(disjoint("hello|world", "world"));
        assertFalse(disjoint("a+", "a{2}"));        // "aa" matches both
        assertFalse(disjoint("[a-z]+", "abc"));
    }

    @Test
    void aPatternIsNeverDisjointFromItself() {
        assertFalse(disjoint("[a-z]+", "[a-z]+"));
        assertFalse(disjoint("\\p{L}\\p{Nd}*", "\\p{L}\\p{Nd}*"));
    }
}
