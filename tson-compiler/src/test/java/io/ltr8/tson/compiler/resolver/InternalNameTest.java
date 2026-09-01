package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.atom.IdentifierParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * [TSON-SCHEMA] §8.2's freshness MUST — an internal name is a valid {@code identifier} — over the content
 * both minting sites splice into one.
 *
 * <p>The case that found it was an HTTP operation: §4.1 names one as the motivating case for the {@code data}
 * kind, and every realistic path carries a slash, so the feature's own worked example could not be templated.
 */
class InternalNameTest {

    /** The head is always a constructor name, so a derived name is an identifier iff every segment is. */
    private static void assertJoinsIntoAnIdentifier(String... segments) {
        StringBuilder derived = new StringBuilder("array");
        for (String segment : segments) {
            derived.append('_').append(InternalName.part(segment));
        }
        derived.append("_1f8d998a");
        assertDoesNotThrow(() -> IdentifierParser.validate(derived.toString()), derived::toString);
    }

    /**
     * ASCII that §7.7 does not admit keeps its readable characters and gains a hash -- so a path still says
     * what it came from, and two texts that sanitise alike stay apart in the readable half rather than
     * relying on the structural hash to tell a reader anything.
     */
    @Test
    void asciiPunctuationKeepsWhatIsReadableAndGainsAHash() {
        assertTrue(InternalName.part("/x").startsWith("x_h"), InternalName.part("/x"));
        assertTrue(InternalName.part("a/b").startsWith("a_b_h"), InternalName.part("a/b"));
        assertNotEquals(InternalName.part("a/b"), InternalName.part("a.b"),
                "two texts that sanitise alike stay apart");
        assertTrue(InternalName.part("/").startsWith("h"), "nothing readable left: the hash alone");
        assertEquals("", InternalName.part(""));
    }

    /**
     * <b>Nothing outside ASCII reaches the name.</b> That is what lets §8.2's hygiene walk judge a minted
     * name like any other: an ASCII name is single-script and inside the identifier profile, so it satisfies
     * all three rules at every restriction level. Admitting {@code XID_Continue} instead would keep the name
     * a legal identifier and still let a document's own text shape a namespace name.
     */
    @Test
    void nonAsciiIsHashedRatherThanSpliced() {
        String cyrillic = InternalName.part("путь");
        assertTrue(cyrillic.matches("h[0-9a-f]{8}"), cyrillic);
        assertNotEquals(cyrillic, InternalName.part("адрес"), "two values stay visibly distinct");
        // A Latin-looking Cyrillic homograph cannot ride into a name and pass for the ASCII spelling.
        assertNotEquals("operation", InternalName.part("\u043eperation"));
        assertTrue(InternalName.part("\u043eperation").matches("h[0-9a-f]{8}"));
    }

    /** ASCII §7.7 admits is copied through untouched: the ordinary case, and the one worth reading. */
    @Test
    void identifierContentIsUntouched() {
        assertEquals("order_line", InternalName.part("order_line"));
        assertEquals("GET", InternalName.part("GET"));
        assertEquals("200", InternalName.part("200"));
        assertEquals("some-name", InternalName.part("some-name"), "§7.7 admits '-' as well");
    }

    /** The whole of the contract, over the shapes that produced a malformed name. */
    @Test
    void everySplicedShapeStillJoinsIntoAnIdentifier() {
        assertJoinsIntoAnIdentifier("/x", "GET", "200", "found");
        assertJoinsIntoAnIdentifier("\"^/orders/[0-9]+$\"");
        assertJoinsIntoAnIdentifier("https://example.test/schema.tn?sha256=abc");
        assertJoinsIntoAnIdentifier("", "/", "///");
        assertJoinsIntoAnIdentifier("путь", "/x");
        assertJoinsIntoAnIdentifier("\u043eperation", "тип");
    }
}
