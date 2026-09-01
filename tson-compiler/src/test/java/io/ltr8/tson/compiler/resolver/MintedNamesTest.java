package io.ltr8.tson.compiler.resolver;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [TSON-SCHEMA] §8.2's freshness MUST — an internal name collides with no other — decided rather than
 * assumed.
 *
 * <p><b>Tested here rather than through a schema, and that is the honest place for it.</b> Triggering a
 * collision from source needs two bindings whose canonical renderings differ and whose 32-bit hashes agree,
 * which cannot be written on purpose through the grammar; what can be tested is the rule, which is what the
 * two minting sites delegate to. The paths that exercise the *dedupe* — one entry for a form written twice,
 * a recursive template tying its knot — run in the resolver's own tests, and would fail loudly here if this
 * refused them.
 */
class MintedNamesTest {

    private static final String FORM = "A5:array(n4:text)";
    private static final String OTHER = "A3:map(n4:text,n5:int32)";

    /**
     * The ordinary case: one form arriving twice. The first claim builds the entry and the second says it is
     * already there, which is the identity discipline — two occurrences of one form are one type.
     */
    @Test
    void theSameDerivationArrivingAgainIsNotACollision() {
        MintedNames minted = new MintedNames();

        assertTrue(minted.claim("array_text_1f8d998a", FORM), "the first claim builds the entry");
        assertFalse(minted.claim("array_text_1f8d998a", FORM), "the second finds it already built");
        assertFalse(minted.claim("array_text_1f8d998a", FORM));
    }

    /**
     * <b>The failure this exists for is not an exception where one was expected, but a silent merge.</b> Two
     * different bindings under one name would have taken each other's entry — one type standing in for
     * another, with nothing said — because deduping by name cannot tell them apart from one form arriving
     * twice.
     */
    @Test
    void twoDerivationsUnderOneNameAreRefused() {
        MintedNames minted = new MintedNames();
        minted.claim("array_text_1f8d998a", FORM);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> minted.claim("array_text_1f8d998a", OTHER));

        assertTrue(thrown.getMessage().contains("array_text_1f8d998a"), thrown::getMessage);
        assertTrue(thrown.getMessage().contains(FORM), () -> "names what was there: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains(OTHER), () -> "and what arrived: " + thrown.getMessage());
    }

    /** Different names never interact, however alike their derivations. */
    @Test
    void distinctNamesAreIndependent() {
        MintedNames minted = new MintedNames();

        assertTrue(minted.claim("array_text_1f8d998a", FORM));
        assertTrue(minted.claim("array_text_c125856a", OTHER));
        assertEquals(false, minted.claim("array_text_1f8d998a", FORM));
    }
}
