package io.ltr8.tson.base.unicode;

import io.ltr8.tson.base.unicode.IdentifierProfile.Base;
import io.ltr8.tson.base.unicode.IdentifierProfile.Normalization;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link IdentifierProfile}'s parameters -- UAX #31's R1 shape, {@code Start Continue* (Medial Continue+)*}, with
 * each set a base adjusted by additions and exclusions, and a required normalization form. {@link
 * IdentifierProfileTest} covers {@link IdentifierProfile#NAME} itself.
 *
 * <p><b>No literal invisible or compatibility character appears in this source</b>; each is an escape.
 */
class IdentifierProfileParametersTest {

    private static void admits(IdentifierProfile profile, String... texts) {
        for (String text : texts) {
            assertTrue(profile.check(text).isEmpty(), () -> "should admit " + text + ": " + profile.check(text));
        }
    }

    private static String refuses(IdentifierProfile profile, String text) {
        return profile.check(text).orElseGet(() -> fail("should refuse: " + text));
    }

    private static IdentifierProfile profile(Base start, Base cont, String startAdd, String continueAdd,
                                             String medial, String exclude, Normalization normalization) {
        return IdentifierProfile.of(start, cont, startAdd, continueAdd, medial, exclude, normalization);
    }

    @Test
    void nameIsTheKernelProfileHoweverTheSetsAreSpelled() {
        assertEquals(IdentifierProfile.NAME, profile(Base.XID, Base.XID, "", "--", "", "", Normalization.NFC));
        assertNotEquals(IdentifierProfile.NAME, profile(Base.XID, Base.XID, "", "", "-", "", Normalization.NFC));
    }

    /** JavaScript's IdentifierName: {@code ID_Start ∪ {$ _}}, {@code ID_Continue ∪ {$}}. */
    @Test
    void additionsExtendTheBase() {
        IdentifierProfile js = profile(Base.ID, Base.ID, "$_", "$", "", "", Normalization.NFC);
        admits(js, "$scope", "_private", "a$b", "camelCase");
        assertTrue(refuses(js, "2d").contains("cannot start"));
        assertTrue(refuses(js, "a-b").contains("cannot appear"));
    }

    /** An ASCII-only name: no base at all, every character listed. */
    @Test
    void aNoneBaseAdmitsOnlyItsAdditions() {
        String lower = "abcdefghijklmnopqrstuvwxyz";
        IdentifierProfile ascii = profile(Base.NONE, Base.NONE, lower, lower + "0123456789_", "", "",
                Normalization.NONE);
        admits(ascii, "order_id", "v2");
        refuses(ascii, "Order");
        refuses(ascii, "café");
    }

    @Test
    void exclusionsRemoveFromBothSets() {
        IdentifierProfile noUnderscore = profile(Base.XID, Base.XID, "", "", "", "_", Normalization.NFC);
        admits(noUnderscore, "orderId");
        assertTrue(refuses(noUnderscore, "order_id").contains("U+005F"));
    }

    /** Kebab case: `-` joins two words, never leads, trails or doubles. */
    @Test
    void aMedialStandsOnlyBetweenTwoOthers() {
        IdentifierProfile kebab = profile(Base.XID, Base.XID, "", "", "-", "", Normalization.NFC);
        admits(kebab, "a", "a-b", "content-type", "x-request-id");
        assertTrue(refuses(kebab, "a-").contains("cannot end"));
        assertTrue(refuses(kebab, "a--b").contains("follows another medial"));
        assertTrue(refuses(kebab, "-a").contains("cannot start"));
    }

    /** The kernel's `-` is Continue, not Medial, so the kernel admits what kebab refuses. */
    @Test
    void theKernelDashIsContinueNotMedial() {
        admits(IdentifierProfile.NAME, "a-", "a--b");
    }

    @Test
    void theNormalizationFormIsRequiredNotApplied() {
        String decomposed = "café";
        String fullWidthA = "ａbc";
        assertTrue(refuses(IdentifierProfile.NAME, decomposed).contains("NFC"));
        admits(profile(Base.XID, Base.XID, "", "", "", "", Normalization.NONE), decomposed);
        IdentifierProfile nfkc = profile(Base.ID, Base.ID, "", "", "", "", Normalization.NFKC);
        assertTrue(refuses(nfkc, fullWidthA).contains("NFKC"));
        admits(profile(Base.ID, Base.ID, "", "", "", "", Normalization.NFC), fullWidthA);
    }

    /** An invisible joiner is refused under every profile, not only the kernel's. */
    @Test
    void theJoinerRuleHoldsUnderEveryProfile() {
        String zwnj = "ab‌c";
        assertTrue(refuses(profile(Base.ID, Base.ID, "", "", "", "", Normalization.NONE), zwnj)
                .contains("§3.1.1.1"));
    }

    @Test
    void anEmptyStartSetIsIncoherent() {
        assertEquals(List.of("the Start set is empty, so no text is an identifier"),
                profile(Base.NONE, Base.XID, "", "", "", "", Normalization.NFC).incoherence());
        assertEquals(1, profile(Base.NONE, Base.XID, "a", "", "", "a", Normalization.NFC).incoherence().size());
        assertEquals(List.of(), profile(Base.NONE, Base.XID, "a", "", "", "", Normalization.NFC).incoherence());
    }

    @Test
    void aMedialThatIsAlsoContinueIsIncoherent() {
        assertEquals(List.of("U+002D is medial and also Start or Continue"),
                profile(Base.XID, Base.XID, "", "-", "-", "", Normalization.NFC).incoherence());
        assertEquals(List.of("U+005F is medial and also Start or Continue"),
                profile(Base.XID, Base.XID, "", "", "_", "", Normalization.NFC).incoherence());
        assertEquals(List.of(), profile(Base.XID, Base.XID, "", "", "_", "_", Normalization.NFC).incoherence());
    }

    /**
     * ID contains XID, and differs from it by exactly the characters {@link Xid}'s exclusion tables name: the two
     * bases agree everywhere else, over every code point.
     */
    @Test
    void theIdBaseIsXidPlusItsNfkcExclusions() {
        int startOnly = 0;
        int continueOnly = 0;
        for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
            if (Xid.isStart(cp)) {
                assertTrue(Xid.isIdStart(cp), () -> "XID_Start outside ID_Start");
            } else if (Xid.isIdStart(cp)) {
                startOnly++;
            }
            if (Xid.isContinue(cp)) {
                assertTrue(Xid.isIdContinue(cp), () -> "XID_Continue outside ID_Continue");
            } else if (Xid.isIdContinue(cp)) {
                continueOnly++;
            }
        }
        assertEquals(24, startOnly);
        assertEquals(20, continueOnly);
    }
}
