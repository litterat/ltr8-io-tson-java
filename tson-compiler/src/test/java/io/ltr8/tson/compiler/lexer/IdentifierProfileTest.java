package io.ltr8.tson.compiler.lexer;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §7.1's UAX #31 profile -- {@code Start = XID_Start ∪ Nd ∪ { - + . }}, {@code Continue = XID_Continue ∪
 * { - + . }} with ZWNJ/ZWJ excluded by that section's own prose.
 *
 * <p>These exist because the JDK's identifier predicates are a <em>different set</em>:
 * {@code Character.isUnicodeIdentifierPart} is {@code ID_Continue} union everything
 * {@code Character.isIdentifierIgnorable} covers, which is all of {@code Cf} plus the non-whitespace C0/C1
 * controls. Standing it in for {@code XID_Continue} put every invisible format character -- the bidi
 * overrides included -- inside identifiers, and every ASCII test still passed (issue #229). A port with real
 * XID tables is what surfaced it, so the cases are pinned here rather than left to the next reader's
 * arithmetic.
 *
 * <p><b>No literal invisible character appears in this source</b>; every one is built from its code point.
 */
class IdentifierProfileTest {

    /** {@code ab<cp>c} -- never a literal invisible character in source. */
    private static String midToken(int cp) {
        return "ab" + new String(Character.toChars(cp)) + "c";
    }

    private static List<Token> tokens(String source) {
        List<Token> all = new Lexer(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8))).tokenize();
        return all.subList(0, all.size() - 1);
    }

    /**
     * The whole point: a format or control character does not continue an identifier. Each of these was one
     * {@code UNQUOTED} token before the fix, so this list is the regression itself.
     */
    @Test
    void formatAndControlCharactersDoNotContinueAnUnquotedToken() {
        int[] refused = {
            0xFEFF,                     // ZWNBSP / BOM -- §7.1 names this one explicitly
            0x200C, 0x200D,             // ZWNJ, ZWJ -- in XID_Continue, excluded by §7.1's prose
            0x00AD, 0x2060,             // SOFT HYPHEN, WORD JOINER
            0x202A, 0x202B, 0x202C, 0x202D, 0x202E,   // bidi embedding/override -- the §9.4 risk
            0x2066, 0x2067, 0x2068, 0x2069,           // bidi isolates
            0x061C,                     // ARABIC LETTER MARK
            0x0001, 0x001B, 0x007F, 0x009F,           // non-whitespace C0/C1 controls
        };
        for (int cp : refused) {
            LexException thrown = assertThrows(LexException.class, () -> tokens(midToken(cp)),
                    () -> "U+%04X should not continue an identifier".formatted(cp));
            assertTrue(thrown.getMessage().contains("U+%04X".formatted(cp)),
                    () -> "U+%04X: " + thrown.getMessage());
        }
    }

    /**
     * And NFC is not what refuses them -- it preserves {@code Cf}, so every one of those tokens is already
     * normalised. Pinned because "the NFC check would have caught it" is the obvious wrong guess.
     */
    @Test
    void nfcNormalizationDoesNotRefuseFormatCharacters() {
        for (int cp : new int[] {0xFEFF, 0x200C, 0x00AD, 0x202E}) {
            assertTrue(Normalizer.isNormalized(midToken(cp), Normalizer.Form.NFC),
                    () -> "U+%04X".formatted(cp));
        }
    }

    /**
     * §7.1's four positions for U+FEFF, together -- three were already right, and the fourth is what #229
     * fixed. Kept as one test because the bug was precisely that one document answered "is this a
     * character?" two different ways depending on the offset.
     */
    @Test
    void theByteOrderMarkIsStrippedLeadingContentInAStringAndAnErrorEverywhereElse() {
        String bom = new String(Character.toChars(0xFEFF));

        List<Token> leading = tokens(bom + "abc");
        assertEquals(1, leading.size());
        assertEquals("abc", leading.getFirst().text());

        List<Token> quoted = tokens("\"ab" + bom + "c\"");
        assertEquals("ab" + bom + "c", quoted.getFirst().text());

        assertThrows(LexException.class, () -> tokens("ab " + bom + " cd"));
        assertThrows(LexException.class, () -> tokens(midToken(0xFEFF)));
    }

    /**
     * The NFKC exclusions, the other direction the JDK predicate differs in: {@code ID_} admits these and
     * {@code XID_} does not. A sample of each table rather than all 24 -- the tables are in {@code Lexer} and
     * the full check is against the UCD, which is not shipped here.
     */
    @Test
    void nfkcExcludedCharactersAreNotIdentifierCharacters() {
        for (int cp : new int[] {0x037A, 0x2E2F, 0x309B, 0xFC5E, 0xFDFA, 0xFE70}) {
            assertThrows(LexException.class, () -> tokens(midToken(cp)),
                    () -> "U+%04X is ID_Continue but not XID_Continue".formatted(cp));
            assertThrows(LexException.class, () -> tokens(new String(Character.toChars(cp)) + "bc"),
                    () -> "U+%04X is ID_Start but not XID_Start".formatted(cp));
        }
    }

    /**
     * U+0E33, U+0EB3, U+FF9E and U+FF9F are the four that are {@code XID_Continue} but not
     * {@code XID_Start}, which is why the two exclusion tables differ in length. They continue a token and
     * cannot start one.
     */
    @Test
    void theContinueOnlyNfkcCharactersContinueButDoNotStart() {
        for (int cp : new int[] {0x0E33, 0x0EB3, 0xFF9E, 0xFF9F}) {
            List<Token> t = tokens(midToken(cp));
            assertEquals(1, t.size(), () -> "U+%04X should continue a token".formatted(cp));
            assertEquals(midToken(cp), t.getFirst().text());
            assertThrows(LexException.class, () -> tokens(new String(Character.toChars(cp)) + "bc"),
                    () -> "U+%04X is not XID_Start".formatted(cp));
        }
    }

    /** Ordinary identifier characters in several scripts still lex, so the subtraction did not overshoot. */
    @Test
    void ordinaryIdentifiersInManyScriptsStillLex() {
        for (String name : List.of("abc", "my_type", "καλημέρα", "日本語", "переменная", "متغير", "a1", "x́")) {
            List<Token> t = tokens(name);
            assertEquals(1, t.size(), name);
            assertEquals(TokenType.UNQUOTED, t.getFirst().type(), name);
            assertEquals(name, t.getFirst().text(), name);
        }
    }

    /** §7.1 asks an implementation to document its Unicode version; this pins that it says so. */
    @Test
    void theSupportedUnicodeVersionIsDeclared() {
        assertEquals("16.0", Xid.UNICODE_VERSION);
    }
}
