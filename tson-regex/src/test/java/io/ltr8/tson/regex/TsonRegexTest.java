package io.ltr8.tson.regex;

import io.ltr8.tson.regex.RegexNode.Alternation;
import io.ltr8.tson.regex.RegexNode.AnyChar;
import io.ltr8.tson.regex.RegexNode.CategoryEscape;
import io.ltr8.tson.regex.RegexNode.CharClass;
import io.ltr8.tson.regex.RegexNode.ClassRange;
import io.ltr8.tson.regex.RegexNode.Literal;
import io.ltr8.tson.regex.RegexNode.Repeat;
import io.ltr8.tson.regex.RegexNode.Sequence;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TsonRegex}: parsing and subset-validating I-Regexp (RFC 9485). Proves the interoperable subset is
 * accepted, that constructs outside it (the whole point of the pin) are rejected, and that a few AST shapes
 * and I-Regexp's own semantic quirks (anchors are literals, {@code .} excludes line terminators) come out
 * right.
 */
class TsonRegexTest {

    @Test
    void acceptsInteroperablePatterns() {
        String[] valid = {
            "",                       // empty matches the empty string
            "abc",
            "a|b|c",
            "(a|b)c",
            "colou?r",
            "a*", "a+", "b?",
            "(ab)+",
            "a{3}", "a{2,4}", "a{2,}",
            "[a-z]", "[A-Za-z0-9]", "[^0-9]", "[abc]",
            "[-a]", "[a-]", "[a.]",   // '-' as first/last is literal; '.' in a class is literal
            ".", "\\.", "\\(", "\\\\", "\\?",
            "\\p{L}", "\\P{Nd}", "\\p{Lu}\\p{Ll}*", "[\\p{L}\\p{Nd}_]",
            "192\\.168\\.0\\.1",
            "café",              // combining/accented letters are ordinary literals
            "😀+",          // a supplementary-plane code point (emoji) is a single atom
        };
        for (String pattern : valid) {
            TsonRegex.parse(pattern); // must not throw
        }
    }

    @Test
    void rejectsConstructsOutsideTheSubset() {
        String[] invalid = {
            "\\d", "\\w", "\\s", "\\D",      // no multi-character escapes
            "a**", "a*?", "*abc",            // stray / non-greedy quantifiers
            "(?:a)", "(?=a)",                // no non-capturing groups or lookaround
            "\\1", "\\b",                    // no back-references or word boundaries
            "[a-z-[aeiou]]",                 // no character-class subtraction
            "[]", "[^]",                     // empty / empty-negated class
            "\\p{IsBasicLatin}",             // no Unicode blocks
            "\\p{Foo}", "\\p{ll}",           // not a valid (case-sensitive) category
            "\\pL",                          // \p must be \p{...}
            "a{2,1}",                        // range out of order
            "a{", "(", "a)", "[a", "\\", "\\p{L",  // malformed
        };
        for (String pattern : invalid) {
            assertThrows(TsonRegexSyntaxException.class, () -> TsonRegex.parse(pattern),
                    () -> "expected '" + pattern + "' to be rejected");
        }
    }

    @Test
    void anchorsAreLiteralsNotAssertions() {
        // I-Regexp has no anchors: ^ and $ are ordinary literal characters.
        assertEquals(new Literal('^'), TsonRegex.parse("^").ast());
        assertEquals(new Literal('$'), TsonRegex.parse("$").ast());
    }

    @Test
    void buildsTheExpectedAst() {
        assertEquals(new Repeat(new Literal('a'), 2, OptionalInt.of(4)), TsonRegex.parse("a{2,4}").ast());

        RegexNode alt = TsonRegex.parse("ab|cd").ast();
        Alternation alternation = assertInstanceOf(Alternation.class, alt);
        assertEquals(2, alternation.alternatives().size());
        assertInstanceOf(Sequence.class, alternation.alternatives().get(0));

        CharClass cls = assertInstanceOf(CharClass.class, TsonRegex.parse("[^a-z0]").ast());
        assertTrue(cls.negated());
        assertEquals(new ClassRange('a', 'z'), cls.members().get(0));
        assertEquals(new Literal('0'), cls.members().get(1));

        CategoryEscape cat = assertInstanceOf(CategoryEscape.class, TsonRegex.parse("\\P{Nd}").ast());
        assertEquals(RegexCategory.Nd, cat.category());
        assertTrue(cat.complement());

        assertInstanceOf(AnyChar.class, TsonRegex.parse(".").ast());
    }

    @Test
    void reportsThePositionOfAFailure() {
        TsonRegexSyntaxException e = assertThrows(TsonRegexSyntaxException.class, () -> TsonRegex.parse("ab\\d"));
        assertEquals(3, e.position());          // the 'd' after the backslash
        assertFalse(e.getMessage().isBlank());
    }
}
