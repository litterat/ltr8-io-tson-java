package io.ltr8.tson.compiler.lexer;

import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.atom.AtomParseException;
import io.ltr8.tson.compiler.atom.IdentifierParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UTS #39 §3.1.1.1's three contexts, as an identifier rule ([TSON-DATA] §7.7 rule 2).
 *
 * <p><b>Every string here is built from code points</b>, never typed: a joiner is invisible, so a literal
 * would be unreviewable and one careless paste from asserting nothing. The names say which script and which
 * of §3.1.1.1's clauses each case exercises.
 */
class JoiningControlsTest {

    private static final String ZWNJ = new String(Character.toChars(0x200C));
    private static final String ZWJ = new String(Character.toChars(0x200D));

    private static String cps(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

    private static String parse(String identifier) {
        return IdentifierParser.INSTANCE.read(new TokenValue(identifier, TokenForm.UNQUOTED));
    }

    private static String refused(String identifier) {
        return assertThrows(AtomParseException.class, () -> parse(identifier)).getMessage();
    }

    // ---- A1: ZWNJ breaking a cursive connection -------------------------------------------------------

    /**
     * Persian {@code کتاب‌ها} ("books"): ZWNJ between HEH (dual-joining) and ALEF (right-joining), which is
     * ordinary spelling rather than decoration -- without it the word is misspelled.
     */
    @Test
    void a1AdmitsAPersianCompoundWhereTheJoinerBreaksACursiveConnection() {
        String books = cps(0x0643, 0x062A, 0x0627, 0x0628) + ZWNJ + cps(0x0647, 0x0627);
        assertEquals(books, parse(books));
    }

    /** The same shape one letter at a time: DUAL + ZWNJ + DUAL is A1's core. */
    @Test
    void a1AdmitsADualJoiningPairAroundTheJoiner() {
        String word = cps(0x0628) + ZWNJ + cps(0x0628);
        assertEquals(word, parse(word));
    }

    /**
     * <b>The attack.</b> Latin has no cursive joining, so a ZWNJ between {@code d} and {@code m} matches no
     * context and renders as nothing at all: {@code ad<ZWNJ>min} is {@code admin} on screen.
     */
    @Test
    void theLatinHomographIsRefused() {
        String spoof = "ad" + ZWNJ + "min";
        assertTrue(refused(spoof).contains("§3.1.1.1"), refused(spoof));
    }

    /** A joiner at the end of a name joins nothing -- there is no right context at all. */
    @Test
    void aTrailingJoinerIsRefused() {
        assertTrue(refused(cps(0x0628) + ZWNJ).contains("§3.1.1.1"));
    }

    /**
     * §3.1.1.1's script restriction, which is the condition that makes A1 safe: the joining types line up
     * here, but the sequence spans two scripts, so the context does not hold.
     */
    @Test
    void a1RefusesAJoinerWhoseContextSpansTwoScripts() {
        // Arabic BEH (dual-joining) on the left, Syriac BETH (dual-joining) on the right.
        String mixed = cps(0x0628) + ZWNJ + cps(0x0712);
        assertTrue(refused(mixed).contains("§3.1.1.1"), refused(mixed));
    }

    // ---- A2 / B: the conjunct contexts ----------------------------------------------------------------

    /**
     * Malayalam, §3.1.1.1's own Figure 2 example: KA + VIRAMA + ZWNJ + SA is a conjunct context, and the
     * form without the ZWNJ is a different (incorrect) word.
     */
    @Test
    void a2AdmitsTheMalayalamConjunctFromTheSpecsOwnExample() {
        String eyewitness = cps(0x0D26, 0x0D43, 0x0D15, 0x0D4D) + ZWNJ + cps(0x0D38, 0x0D3E, 0x0D15, 0x0D4D,
                0x0D37, 0x0D3F);
        assertEquals(eyewitness, parse(eyewitness));
    }

    /** Devanagari KA + VIRAMA + ZWNJ + KA -- the same clause in a second script. */
    @Test
    void a2AdmitsADevanagariConjunct() {
        String word = cps(0x0915, 0x094D) + ZWNJ + cps(0x0915);
        assertEquals(word, parse(word));
    }

    /** B: ZWJ after a virama, not followed by a dependent vowel -- Sinhala, §3.1.1.1's Figure 3 shape. */
    @Test
    void bAdmitsAZwjInAConjunctContext() {
        String word = cps(0x0DC1, 0x0DCA) + ZWJ + cps(0x0DBB);
        assertEquals(word, parse(word));
    }

    /** A ZWNJ with no virama before it is in no conjunct context, whatever the script. */
    @Test
    void a2RefusesAJoinerWithNoViramaBeforeIt() {
        String word = cps(0x0915) + ZWNJ + cps(0x0915);
        assertTrue(refused(word).contains("§3.1.1.1"), refused(word));
    }

    /** A ZWJ *is* refused where B's negative lookahead bites: followed by a dependent vowel. */
    @Test
    void bRefusesAZwjFollowedByADependentVowel() {
        String word = cps(0x0915, 0x094D) + ZWJ + cps(0x093E);
        assertTrue(refused(word).contains("§3.1.1.1"), refused(word));
    }

    /** The clauses are per-joiner: ZWJ does not get A1, which is a ZWNJ rule. */
    @Test
    void aZwjDoesNotInheritTheCursiveBreakContext() {
        String word = cps(0x0628) + ZWJ + cps(0x0628);
        assertTrue(refused(word).contains("§3.1.1.1"), refused(word));
    }

    /** Ordinary names are untouched -- the rule only ever looks at a joiner. */
    @Test
    void anOrdinaryNameIsUnaffected() {
        assertEquals("order_line", parse("order_line"));
    }
}
