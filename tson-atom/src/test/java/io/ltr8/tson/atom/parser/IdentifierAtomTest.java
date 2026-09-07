package io.ltr8.tson.atom.parser;

import io.ltr8.tson.atom.AtomParseException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code identifier} atom -- what §7.7's profile becomes at a <em>typed position</em>.
 *
 * <p>The profile itself is {@code IdentifierProfile}'s and is tested there. What is this atom's own is the
 * verdict: at a position typed {@code identifier}, a name the profile refuses is a parse failure. Every
 * other caller of the profile owes a different answer, which is why the profile reports rather than throws.
 */
class IdentifierAtomTest {

    @Test
    void readReturnsANameTheProfileAdmits() {
        assertEquals("order_id", IdentifierAtom.INSTANCE.read("order_id"));
    }

    @Test
    void readRaisesAParseFailureForOneItDoesNot() {
        AtomParseException refused =
                assertThrows(AtomParseException.class, () -> IdentifierAtom.INSTANCE.read("2fast"));

        assertEquals("an identifier", refused.expected());
        assertTrue(refused.getMessage().contains("never begins with a digit"), refused.getMessage());
    }

    /** The profile's own message travels, so the position adds a verdict and not a second explanation. */
    @Test
    void theRefusalCarriesTheProfilesOwnMessage() {
        assertEquals(io.ltr8.tson.base.unicode.IdentifierProfile.validate("2fast").orElseThrow(),
                assertThrows(AtomParseException.class, () -> IdentifierAtom.INSTANCE.read("2fast")).getMessage());
    }

    @Test
    void writeIsTheIdentity() {
        assertEquals("anything", IdentifierAtom.INSTANCE.write("anything"));
    }
}
