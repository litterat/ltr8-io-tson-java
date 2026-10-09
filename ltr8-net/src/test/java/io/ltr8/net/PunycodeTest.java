package io.ltr8.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** RFC 3492's Punycode, against §7.1's sample strings. Characters beyond ASCII are escapes. */
class PunycodeTest {

    private static void roundTrips(String unicode, String encoded) {
        assertEquals(encoded, Punycode.encode(unicode));
        assertEquals(unicode, Punycode.decode(encoded));
    }

    /** §7.1 (A), Arabic (Egyptian). */
    @Test
    void arabic() {
        roundTrips("ليهمابتكلموشعرب"
                + "ي؟", "egbpdaj6bu4bxfgehfvwxn");
    }

    /** §7.1 (B), Chinese (simplified). */
    @Test
    void chinese() {
        roundTrips("他们为什么不说中文", "ihqwcrb4cv8a8dqg056pqjye");
    }

    /** §7.1 (L), mixing basic and non-basic code points, with uppercase basic ones kept as given. */
    @Test
    void mixedBasicAndNonBasic() {
        roundTrips("3年B組金八先生", "3B-ww4c5e180e575a65lsy2b");
    }

    /** §7.1 (Q), a code point beyond the BMP-free samples: the Japanese "そのスピードで". */
    @Test
    void japanese() {
        roundTrips("そのスピードで", "d9juau41awczczp");
    }

    @Test
    void theCommonCase() {
        roundTrips("bücher", "bcher-kva");
        roundTrips("ü", "tda");
    }

    @Test
    void codePointsBeyondTheBmp() {
        String s = "a" + Character.toString(0x1F600) + "b";
        assertEquals(s, Punycode.decode(Punycode.encode(s)));
    }

    @Test
    void notPunycodeDecodesToNull() {
        assertNull(Punycode.decode("bcher-kv!"));
        assertNull(Punycode.decode("bücher-kva"));
        assertNull(Punycode.decode("99999999999"));
    }
}
