package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.atom.IdentifierParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
            derived.append('_').append(InternalName.segment(segment));
        }
        derived.append("_1f8d998a");
        assertDoesNotThrow(() -> IdentifierParser.validate(derived.toString()), derived::toString);
    }

    @Test
    void punctuationBecomesOneSeparatorAndTheEdgesAreTrimmed() {
        assertEquals("x", InternalName.segment("/x"), "a path's leading slash joins as one separator");
        assertEquals("a_b", InternalName.segment("a/b"));
        assertEquals("a_b", InternalName.segment("a///b"), "a run collapses rather than repeating");
        assertEquals("orders", InternalName.segment("\"orders\""));
        assertEquals("", InternalName.segment("/"), "wholly unadmitted contributes only its own separator");
        assertEquals("", InternalName.segment(""));
    }

    /**
     * What {@code XID_Continue} admits is copied through untouched, which is the half that matters for an
     * author writing outside Latin script: their content survives into the readable name.
     */
    @Test
    void identifierContentIsUntouched() {
        assertEquals("order_line", InternalName.segment("order_line"));
        assertEquals("GET", InternalName.segment("GET"));
        assertEquals("200", InternalName.segment("200"));
        assertEquals("some-name", InternalName.segment("some-name"), "§7.7 admits '-' as well");
        assertEquals("путь", InternalName.segment("путь"));
    }

    /** The whole of the contract, over the shapes that produced a malformed name. */
    @Test
    void everySplicedShapeStillJoinsIntoAnIdentifier() {
        assertJoinsIntoAnIdentifier("/x", "GET", "200", "found");
        assertJoinsIntoAnIdentifier("\"^/orders/[0-9]+$\"");
        assertJoinsIntoAnIdentifier("https://example.test/schema.tn?sha256=abc");
        assertJoinsIntoAnIdentifier("", "/", "///");
        assertJoinsIntoAnIdentifier("путь", "/x");
    }
}
