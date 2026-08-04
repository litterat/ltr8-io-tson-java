package io.ltr8.tson.regex;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TsonRegex#matches}: full-match (whole-string) I-Regexp matching. Covers the combinators and
 * quantifiers, Unicode category and code-point handling, I-Regexp's own quirks (anchors are literals,
 * {@code .} excludes line terminators), and -- the point of a Thompson NFA -- that a pattern which hangs a
 * backtracking engine runs in linear time here.
 */
class TsonRegexMatchTest {

    private static boolean matches(String pattern, String input) {
        return TsonRegex.parse(pattern).matches(input);
    }

    @Test
    void matchesTheWholeStringNotASubstring() {
        assertTrue(matches("abc", "abc"));
        assertFalse(matches("abc", "abcd"));
        assertFalse(matches("abc", "ab"));
        assertFalse(matches("abc", "xabc"));
    }

    @Test
    void alternationAndGrouping() {
        assertTrue(matches("a|b|c", "b"));
        assertFalse(matches("a|b|c", "d"));
        assertTrue(matches("(ab)+", "abab"));
        assertFalse(matches("(ab)+", "aba"));
        assertTrue(matches("(a|b)*c", "aabbc"));
        assertTrue(matches("(a|b)*c", "c"));
        assertFalse(matches("(a|b)*c", "aab"));
    }

    @Test
    void quantifiers() {
        assertTrue(matches("a*", ""));
        assertTrue(matches("a*", "aaa"));
        assertFalse(matches("a+", ""));
        assertTrue(matches("a+", "aaa"));
        assertTrue(matches("colou?r", "color"));
        assertTrue(matches("colou?r", "colour"));
        assertFalse(matches("colou?r", "colouur"));
        assertTrue(matches("a{2,3}", "aa"));
        assertTrue(matches("a{2,3}", "aaa"));
        assertFalse(matches("a{2,3}", "a"));
        assertFalse(matches("a{2,3}", "aaaa"));
        assertTrue(matches("a{2,}", "aaaaa"));
        assertTrue(matches("a{3}", "aaa"));
    }

    @Test
    void characterClassesAndDot() {
        assertTrue(matches("[a-z]+", "abc"));
        assertFalse(matches("[a-z]+", "abc1"));
        assertFalse(matches("[a-z]+", ""));
        assertTrue(matches("[^0-9]", "a"));
        assertFalse(matches("[^0-9]", "5"));
        assertTrue(matches("a.c", "abc"));
        assertTrue(matches("a.c", "a c"));
        assertFalse(matches("a.c", "a\nc")); // '.' excludes line terminators
        assertFalse(matches(".", "\n"));
        assertFalse(matches(".", ""));
    }

    @Test
    void unicodeCategoriesAndCodePoints() {
        assertTrue(matches("\\p{Nd}+", "2026"));
        assertTrue(matches("\\p{Nd}", "٥"));      // Arabic-Indic digit five is also Nd
        assertFalse(matches("\\p{Nd}+", "12a"));
        assertTrue(matches("\\p{Lu}\\p{Ll}*", "Hello"));
        assertFalse(matches("\\p{Lu}\\p{Ll}*", "hello")); // must start uppercase
        assertTrue(matches("\\P{Nd}", "a"));
        assertFalse(matches("\\P{Nd}", "5"));
        assertTrue(matches("café", "café"));  // accented literal
        assertTrue(matches("😀+", "😀😀")); // supplementary-plane atom (emoji)
        assertFalse(matches("😀+", "😀x"));
    }

    @Test
    void anchorsAreLiteralsAndTheEmptyPatternMatchesOnlyEmpty() {
        assertTrue(matches("^a$", "^a$"));   // ^ and $ are literal characters in I-Regexp
        assertFalse(matches("^a$", "a"));
        assertTrue(matches("", ""));
        assertFalse(matches("", "x"));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void isLinearTimeWhereABacktrackingEngineWouldHang() {
        // (a+)+b over a long run of 'a' with no 'b' is a classic catastrophic-backtracking case; a Thompson
        // NFA decides it in linear time. A backtracking engine would not return before the timeout.
        String input = "a".repeat(40);
        assertFalse(matches("(a+)+b", input));
        assertTrue(matches("(a+)+b", input + "b"));
    }
}
