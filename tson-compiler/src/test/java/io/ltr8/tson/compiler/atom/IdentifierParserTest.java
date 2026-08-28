package io.ltr8.tson.compiler.atom;

import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code identifier} contract (issue #231): {@code Start = XID_Start}, {@code Continue = XID_Continue ∪
 * { - }}, in NFC. This replaced a parser that returned {@code token.text()} unvalidated, so every rejection
 * below is a name the kernel accepted until now at every naming position it types.
 *
 * <p><b>No literal invisible character appears in this source</b>; each is built from its code point.
 */
class IdentifierParserTest {

    private static String read(String text) {
        return IdentifierParser.INSTANCE.read(new TokenValue(text, TokenForm.UNQUOTED));
    }

    private static String rejects(String text) {
        return assertThrows(AtomParseException.class, () -> read(text), () -> "should reject: " + text)
                .getMessage();
    }

    @Test
    void ordinaryIdentifiersInManyScriptsAreAccepted() {
        for (String name : new String[] {"a", "order", "my_type", "my-field", "order2", "OPEN",
                                         "καλή", "日本語", "пользователь", "id_пользователя"}) {
            assertEquals(name, read(name), name);
        }
    }

    /** Mixed script is deliberately permitted: `id_пользователя` above is the case, and it is the common one. */
    @Test
    void mixedScriptIsPermitted() {
        assertEquals("url_адрес", read("url_адрес"));
    }

    /**
     * Start drops the four characters §7.1 puts in token-Start for the number grammar's sake, which is what
     * makes a numeric name impossible and subsumes [TSON-SCHEMA] §12.1's own rule.
     */
    @Test
    void anIdentifierNeverStartsWithADigitOrASign() {
        for (String text : new String[] {"42", "1abc", "-foo", "+foo", ".inf", "0x1F"}) {
            assertTrue(rejects(text).contains("never begins with a digit or a sign"), text);
        }
    }

    /** `_` is not in XID_Start, and is deliberately not added back -- keeping every identifier spellable unquoted. */
    @Test
    void anIdentifierDoesNotStartWithAnUnderscore() {
        assertTrue(rejects("_id").contains("cannot start an identifier"));
        assertEquals("a_id", read("a_id"), "but it continues one");
    }

    /** `.` is reserved as a future identifier separator rather than spent as an identifier character. */
    @Test
    void aDotIsReservedAndDoesNotContinueAnIdentifier() {
        assertTrue(rejects("a.b").contains("cannot appear in an identifier"));
        assertEquals("a-b", read("a-b"), "but '-' does");
    }

    /** Everything invisible falls out of XID membership rather than needing a clause of its own. */
    @Test
    void whitespaceControlsAndFormatCharactersAreRejected() {
        for (int cp : new int[] {' ', '\t', 0x00A0, 0x0001, 0x007F, 0xFEFF, 0x00AD, 0x2060, 0x202E, 0x2066}) {
            String text = "ab" + new String(Character.toChars(cp)) + "c";
            assertTrue(rejects(text).contains("U+%04X".formatted(cp)),
                    () -> "U+%04X".formatted(cp));
        }
    }

    @Test
    void anEmptyNameIsRejected() {
        assertTrue(rejects("").contains("may not be empty"));
    }

    @Test
    void aNameThatIsNotNfcIsRejected() {
        String nfd = "cafe" + new String(Character.toChars(0x0301));
        assertTrue(rejects(nfd).contains("not NFC-normalized"));
        assertEquals("café", read("café"), "the NFC spelling is fine");
    }

    /**
     * ZWNJ and ZWJ <em>are</em> {@code XID_Continue}, so the profile admits them even though the lexer
     * currently does not emit a token containing one. Written to the property rather than to what the lexer
     * happens to permit, so this layer is already right when the lexer adopts UTS #39 §3.1.1.1's contextual
     * rule (`SPEC-FEEDBACK.md` #14).
     */
    @Test
    void theJoinersArePermittedByTheProfileEvenThoughTheLexerRefusesThem() {
        String zwnj = "ab" + new String(Character.toChars(0x200C)) + "c";
        assertEquals(zwnj, read(zwnj));
    }

    @Test
    void writeIsTheIdentity() {
        assertEquals("anything", IdentifierParser.INSTANCE.write("anything"));
    }
}
