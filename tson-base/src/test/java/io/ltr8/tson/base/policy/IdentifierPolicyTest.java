package io.ltr8.tson.base.policy;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.unicode.IdentifierProfile;
import io.ltr8.tson.base.unicode.IdentifierProfile.Base;
import io.ltr8.tson.base.unicode.IdentifierProfile.Normalization;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [TSON-DATA] §8.2's identifier policy: the per-name rules judged under a name's own profile, the unit, and the
 * switch for skeleton distinctness.
 *
 * <p>Mixed-script names are built from code points, never typed: the whole subject is spellings that look
 * alike, so a literal would be unreviewable.
 */
class IdentifierPolicyTest {

    private static final String CYR_A = new String(Character.toChars(0x0430));   // а
    private static final String CYR_P = new String(Character.toChars(0x043F));   // п
    private static final String GREEK_ALPHA = new String(Character.toChars(0x03B1));
    private static final String HAN = new String(Character.toChars(0x65E5));     // 日
    /** U+0132, a Latin ligature: {@code XID_Continue} and {@code Identifier_Status=Restricted}. */
    private static final String RESTRICTED = new String(Character.toChars(0x0132));

    private static final IdentifierPolicy PER_SEGMENT =
            IdentifierPolicy.of(ScriptPolicy.highlyRestrictive()).perSegment();

    /** A JavaScript-like profile: {@code $} and {@code _} start a name, {@code $} continues one. */
    private static final IdentifierProfile JS =
            IdentifierProfile.of(Base.XID, Base.XID, "$_", "$", "", "", Normalization.NFC);

    /** A dotted profile: {@code .} stands between two words. */
    private static final IdentifierProfile DOTTED =
            IdentifierProfile.of(Base.XID, Base.XID, "", "", ".", "", Normalization.NFC);

    private static List<Diagnostic.Code> codes(IdentifierPolicy policy, String name, IdentifierProfile profile) {
        return policy.judge(name, profile).stream().map(IdentifierPolicy.Violation::code).toList();
    }

    private static boolean accepts(IdentifierPolicy policy, String name, IdentifierProfile profile) {
        return policy.judge(name, profile).isEmpty();
    }

    /** The first relaxation: the unit, not the level. It keeps every rejection that matters. */
    @Test
    void perSegmentKeepsTheHomographsAndAdmitsTheCompounds() {
        assertTrue(accepts(PER_SEGMENT, "id_" + CYR_P, IdentifierProfile.NAME));
        assertTrue(accepts(PER_SEGMENT, "alpha-" + GREEK_ALPHA, IdentifierProfile.NAME));
        assertTrue(accepts(PER_SEGMENT, HAN + HAN + "id", IdentifierProfile.NAME));
        assertEquals(List.of(Diagnostic.Code.RESTRICTED_SCRIPT), codes(PER_SEGMENT, CYR_A + "dmin",
                IdentifierProfile.NAME));
        assertEquals(List.of(Diagnostic.Code.RESTRICTED_SCRIPT), codes(PER_SEGMENT, "id_" + CYR_A + "dmin",
                IdentifierProfile.NAME));
        assertFalse(accepts(IdentifierPolicy.defaults(), "id_" + CYR_P, IdentifierProfile.NAME));
    }

    /**
     * A name's segments are divided by its own profile's punctuation: {@code _}, and what the profile adds that
     * is not {@code XID_Continue}. So {@code $} divides a JavaScript-like name and {@code .} a dotted one, and
     * neither divides a name under §7.7's profile, which adds only {@code -}.
     */
    @Test
    void segmentsAreDividedByTheProfilesOwnSeparators() {
        assertTrue(accepts(PER_SEGMENT, "id$" + CYR_P, JS));
        assertTrue(accepts(PER_SEGMENT, "com." + CYR_P, DOTTED));
        assertFalse(accepts(PER_SEGMENT, "id$" + CYR_P, IdentifierProfile.NAME));
        assertFalse(accepts(PER_SEGMENT, "com." + CYR_P, IdentifierProfile.NAME));
        assertFalse(accepts(PER_SEGMENT, "id$" + CYR_A + "dmin", JS), "a homograph inside one word still is one");

        assertTrue(JS.separates('$'));
        assertTrue(JS.separates('_'));
        assertFalse(JS.separates('-'));
        assertTrue(IdentifierProfile.NAME.separates('-'));
        assertFalse(IdentifierProfile.NAME.separates('.'));
    }

    /** Both rules are judged, and each one failed is reported: two rules, two fixes. */
    @Test
    void aNameFailingBothRulesIsReportedUnderBoth() {
        assertEquals(List.of(Diagnostic.Code.RESTRICTED_CHARACTER, Diagnostic.Code.RESTRICTED_SCRIPT),
                codes(IdentifierPolicy.defaults(), "a" + RESTRICTED + CYR_A, IdentifierProfile.NAME));
    }

    /** A passing name allocates no list: the empty answer is the one constant. */
    @Test
    void aPassingNameIsTheEmptyList() {
        assertSame(List.of(), IdentifierPolicy.defaults().judge("admin", IdentifierProfile.NAME));
    }

    /** Level 6 alone drops the restricted-character rule; level 5 keeps it, as §8.2 says. */
    @Test
    void onlyUnrestrictedDropsTheRestrictedCharacterRule() {
        String name = "a" + RESTRICTED + "b";
        assertEquals(List.of(Diagnostic.Code.RESTRICTED_CHARACTER),
                codes(IdentifierPolicy.of(ScriptPolicy.scriptsUnchecked()), name, IdentifierProfile.NAME));
        assertEquals(List.of(), codes(IdentifierPolicy.of(ScriptPolicy.unrestricted()), name,
                IdentifierProfile.NAME));
    }

    /** Skeleton distinctness is on by default, off only when stated, and untouched by the level. */
    @Test
    void skeletonDistinctnessIsItsOwnSwitch() {
        assertTrue(IdentifierPolicy.defaults().appliesSkeletonDistinctness());
        assertTrue(IdentifierPolicy.of(ScriptPolicy.unrestricted()).appliesSkeletonDistinctness());
        assertFalse(IdentifierPolicy.defaults().withSkeletonDistinctness(false).appliesSkeletonDistinctness());
        assertFalse(IdentifierPolicy.none().appliesSkeletonDistinctness());
        assertEquals(List.of(), codes(IdentifierPolicy.none(), "a" + RESTRICTED + CYR_A, IdentifierProfile.NAME));
    }

    @Test
    void aPolicyIsAValue() {
        assertEquals(PER_SEGMENT, IdentifierPolicy.of(ScriptPolicy.highlyRestrictive()).perSegment());
        assertNotEquals(PER_SEGMENT, IdentifierPolicy.defaults());
        assertNotEquals(IdentifierPolicy.defaults(), IdentifierPolicy.defaults().withSkeletonDistinctness(false));
        assertEquals("HIGHLY_RESTRICTIVE per segment without skeleton distinctness",
                PER_SEGMENT.withSkeletonDistinctness(false).toString());
        assertEquals("HIGHLY_RESTRICTIVE", IdentifierPolicy.defaults().toString());
    }
}
