package io.ltr8.tson.compiler;

import io.ltr8.tson.tree.TsonMap;
import io.ltr8.tson.tree.TsonRecord;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §2.5 and §2.6 define the identity of a field name and of a scalar map key by their NFC-normalised text,
 * whichever form spelled them: "two field names are identical if they produce the same NFC-normalized string
 * after escape processing". This compared raw text, so a name in NFC and the same name in NFD were two names
 * -- both rendering identically in every editor (issue #233).
 *
 * <p><b>No literal composed or decomposed character appears in this source</b>: both spellings are built
 * from code points, so the difference cannot be lost to an editor normalising the file.
 */
class NfcNameIdentityTest {

    /** {@code café} precomposed -- U+00E9. */
    private static final String NFC = "caf" + new String(Character.toChars(0x00E9));

    /** {@code café} decomposed -- {@code e} + U+0301 COMBINING ACUTE ACCENT. The same name, per §2.5. */
    private static final String NFD = "cafe" + new String(Character.toChars(0x0301));

    private static TsonValue read(String source) {
        return new TsonTreeReader().read(source);
    }

    @Test
    void theTwoSpellingsAreOneFieldName() {
        TsonReadException thrown = assertThrows(TsonReadException.class,
                () -> read("{ \"" + NFC + "\": 1  \"" + NFD + "\": 2 }"));
        assertTrue(thrown.getMessage().contains("duplicate field"), thrown.getMessage());
    }

    @Test
    void theTwoSpellingsAreOneMapKey() {
        TsonReadException thrown = assertThrows(TsonReadException.class,
                () -> read("{ \"" + NFC + "\" => 1  \"" + NFD + "\" => 2 }"));
        assertTrue(thrown.getMessage().contains("duplicate key"), thrown.getMessage());
    }

    /**
     * A name is stored in the form its identity is defined by, so a decomposed spelling reads back
     * composed and an ordinary lookup finds it. Without this a caller would have to know which spelling the
     * document happened to use, which is precisely what §2.5 says they do not.
     */
    @Test
    void aDecomposedNameIsStoredAndFoundInItsNormalisedForm() {
        TsonRecord record = (TsonRecord) read("{ \"" + NFD + "\": 1 }");

        assertEquals(List.of(NFC), List.copyOf(record.fields().keySet()));
        assertEquals(1, record.get(NFC).asInt().orElseThrow());
    }

    /** §2.5's own example, which held before and still does: quoting is not part of a name's identity. */
    @Test
    void aBareNameAndItsQuotedSpellingAreOneName() {
        assertTrue(assertThrows(TsonReadException.class, () -> read("{ name: 1  \"name\": 2 }"))
                .getMessage().contains("duplicate field"));
    }

    /**
     * A map <em>key</em> keeps the content it was written with -- §2.6 makes keys data values, and a value's
     * content is its own. Only its identity is normalised, which is what the duplicate test above asserts.
     */
    @Test
    void aMapKeyKeepsItsWrittenFormEvenWhereIdentityIsNormalised() {
        TsonMap map = (TsonMap) read("{ \"" + NFD + "\" => 1 }");

        assertEquals(NFD, map.entries().getFirst().key().asString().orElseThrow(),
                "the key is a value and keeps its bytes; only comparison normalises");
    }

    /** And an unquoted name was never at risk: the lexer refuses a non-NFC unquoted token outright (§7.2.1). */
    @Test
    void anUnquotedNonNfcTokenIsStillALexerError() {
        assertThrows(TsonReadException.class, () -> read("{ " + NFD + ": 1 }"));
    }
}
