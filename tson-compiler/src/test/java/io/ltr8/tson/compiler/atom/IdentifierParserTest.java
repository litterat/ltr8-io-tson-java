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

    /** Obsolete and technical characters, which XID admits and the General Security Profile does not. */
    @Test
    void restrictedCharactersAreRejected() {
        for (int cp : new int[] {0x07E8, 0xA610, 0x1B6B}) {
            String text = "ab" + new String(Character.toChars(cp)) + "c";
            assertTrue(rejects(text).contains("Identifier_Status=Restricted"), () -> "U+%04X".formatted(cp));
        }
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
     * ZWNJ and ZWJ are {@code XID_Continue} and {@code Identifier_Status=Restricted}, and the second is the
     * base rule: UTS #39 makes them Restricted, and §3.1.1.1 is the carve-out that re-admits them where they
     * have a shaping effect. In Latin they never do -- {@code ab<ZWNJ>c} renders as {@code abc} -- so the
     * carve-out cannot apply and the name is refused. {@code JoiningControlsTest} covers the contexts where
     * it does apply.
     */
    @Test
    void aJoinerInLatinIsRefusedBecauseNoContextAdmitsItThere() {
        String zwnj = "ab" + new String(Character.toChars(0x200C)) + "c";
        assertTrue(rejects(zwnj).contains("§3.1.1.1"), rejects(zwnj));
    }

    @Test
    void writeIsTheIdentity() {
        assertEquals("anything", IdentifierParser.INSTANCE.write("anything"));
    }
}
