package io.ltr8.tson.compiler.lexer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UTS #39 §3.1's {@code Identifier_Status}. The table is flattened inclusive ranges searched by bisection,
 * so the boundary cases are the ones worth pinning: a range's first and last code point are both inside it,
 * which an earlier version of the lookup got wrong for the last -- {@code Z}, the end of {@code A..Z}, came
 * back Restricted.
 */
class IdentifierStatusTest {

    @Test
    void ordinaryIdentifierCharactersAreAllowed() {
        for (int cp : new int[] {'a', 'z', 'A', 'Z', '0', '9', '_', '-',
                                 0x00E9, 0x0430, 0x03B1, 0x4E00, 0x0E01, 0x0905}) {
            assertTrue(IdentifierStatus.isAllowed(cp), () -> "U+%04X".formatted(cp));
        }
    }

    /** Every boundary of every range, both ends -- the class of bug this had. */
    @Test
    void bothEndsOfEveryRangeAreInside() {
        for (int cp : new int[] {'A', 'Z', 'a', 'z', '0', '9'}) {
            assertTrue(IdentifierStatus.isAllowed(cp), () -> "U+%04X".formatted(cp));
        }
        assertFalse(IdentifierStatus.isAllowed('A' - 1), "the code point below a range start is outside");
        assertFalse(IdentifierStatus.isAllowed('Z' + 1), "the code point above a range end is outside");
    }

    /** The joiners are Restricted here, which is what lets the profile drop §7.1's hand-picked exclusion. */
    @Test
    void theJoinersAreRestricted() {
        assertFalse(IdentifierStatus.isAllowed(0x200C));
        assertFalse(IdentifierStatus.isAllowed(0x200D));
    }

    /** Obsolete, technical and limited-use characters -- what this narrowing is actually for. */
    @Test
    void obsoleteAndTechnicalCharactersAreRestricted() {
        for (int cp : new int[] {0x07E8, 0xA610, 0x1B6B, 0x0740, 0x00AD, 0xFEFF, 0x202E}) {
            assertFalse(IdentifierStatus.isAllowed(cp), () -> "U+%04X".formatted(cp));
        }
    }
}
