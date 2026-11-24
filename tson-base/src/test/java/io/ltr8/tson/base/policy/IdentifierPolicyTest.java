package io.ltr8.tson.base.policy;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.unicode.IdentifierProfile;
import io.ltr8.unicode.IdentifierProfile.Base;
import io.ltr8.unicode.Normalization;
import io.ltr8.unicode.RestrictionLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static java.lang.Character.UnicodeScript.CYRILLIC;
import static java.lang.Character.UnicodeScript.LATIN;
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

    /** [TSON-DATA] §7.7's profile, as the kernel's {@code meta.identifier} declares it. */
    private static final IdentifierProfile NAME =
            IdentifierProfile.of(Base.XID, Base.XID, "", "-", "", "", Normalization.NFC);

    private static final String CYR_A = new String(Character.toChars(0x0430));   // а
    private static final String CYR_P = new String(Character.toChars(0x043F));   // п
    private static final String GREEK_ALPHA = new String(Character.toChars(0x03B1));
    private static final String HAN = new String(Character.toChars(0x65E5));     // 日
    /** U+0132, a Latin ligature: {@code XID_Continue} and {@code Identifier_Status=Restricted}. */
    private static final String RESTRICTED = new String(Character.toChars(0x0132));

    private static final IdentifierPolicy PER_SEGMENT =
            IdentifierPolicy.of(RestrictionLevel.HIGHLY_RESTRICTIVE).perSegment();

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
        assertTrue(accepts(PER_SEGMENT, "id_" + CYR_P, NAME));
        assertTrue(accepts(PER_SEGMENT, "alpha-" + GREEK_ALPHA, NAME));
        assertTrue(accepts(PER_SEGMENT, HAN + HAN + "id", NAME));
        assertEquals(List.of(Diagnostic.Code.RESTRICTED_SCRIPT), codes(PER_SEGMENT, CYR_A + "dmin",
                NAME));
        assertEquals(List.of(Diagnostic.Code.RESTRICTED_SCRIPT), codes(PER_SEGMENT, "id_" + CYR_A + "dmin",
                NAME));
        assertFalse(accepts(IdentifierPolicy.defaults(), "id_" + CYR_P, NAME));
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
        assertFalse(accepts(PER_SEGMENT, "id$" + CYR_P, NAME));
        assertFalse(accepts(PER_SEGMENT, "com." + CYR_P, NAME));
        assertFalse(accepts(PER_SEGMENT, "id$" + CYR_A + "dmin", JS), "a homograph inside one word still is one");

        assertTrue(JS.separates('$'));
        assertTrue(JS.separates('_'));
        assertFalse(JS.separates('-'));
        assertTrue(NAME.separates('-'));
        assertFalse(NAME.separates('.'));
    }

    /** Both rules are judged, and each one failed is reported: two rules, two fixes. */
    @Test
    void aNameFailingBothRulesIsReportedUnderBoth() {
        assertEquals(List.of(Diagnostic.Code.RESTRICTED_CHARACTER, Diagnostic.Code.RESTRICTED_SCRIPT),
                codes(IdentifierPolicy.defaults(), "a" + RESTRICTED + CYR_A, NAME));
    }

    /** A passing name allocates no list: the empty answer is the one constant. */
    @Test
    void aPassingNameIsTheEmptyList() {
        assertSame(List.of(), IdentifierPolicy.defaults().judge("admin", NAME));
    }

    /**
     * Level 6 alone drops the restricted-character rule; level 5 keeps it, as §8.2 says. A deployment meaning
     * "stop checking scripts" wants level 5.
     */
    @Test
    void onlyUnrestrictedDropsTheRestrictedCharacterRule() {
        assertTrue(IdentifierPolicy.of(RestrictionLevel.MINIMALLY_RESTRICTIVE).appliesIdentifierProfile(),
                "level 5 keeps the identifier profile");
        assertFalse(IdentifierPolicy.of(RestrictionLevel.UNRESTRICTED).appliesIdentifierProfile(),
                "level 6 drops it -- §5.2 says so");
        String name = "a" + RESTRICTED + "b";
        assertEquals(List.of(Diagnostic.Code.RESTRICTED_CHARACTER),
                codes(IdentifierPolicy.of(RestrictionLevel.MINIMALLY_RESTRICTIVE), name, NAME));
        assertEquals(List.of(), codes(IdentifierPolicy.of(RestrictionLevel.UNRESTRICTED), name,
                NAME));
    }

    /** Skeleton distinctness is on by default, off only when stated, and untouched by the level. */
    @Test
    void skeletonDistinctnessIsItsOwnSwitch() {
        assertTrue(IdentifierPolicy.defaults().appliesSkeletonDistinctness());
        assertTrue(IdentifierPolicy.of(RestrictionLevel.UNRESTRICTED).appliesSkeletonDistinctness());
        assertFalse(IdentifierPolicy.defaults().withSkeletonDistinctness(false).appliesSkeletonDistinctness());
        assertFalse(IdentifierPolicy.none().appliesSkeletonDistinctness());
        assertEquals(List.of(), codes(IdentifierPolicy.none(), "a" + RESTRICTED + CYR_A, NAME));
    }

    /** The narrowest relaxation: the named combination is admitted in each unit, and nothing else is widened. */
    @Test
    void anAdditionalPermittedSetAdmitsOnlyThatCombination() {
        IdentifierPolicy bilingual = IdentifierPolicy.of(RestrictionLevel.HIGHLY_RESTRICTIVE).permitting(LATIN, CYRILLIC);

        assertTrue(accepts(bilingual, "id_" + CYR_P, NAME));
        assertTrue(accepts(bilingual, CYR_A + "dmin", NAME), "deliberately: the deployment said so");
        assertEquals(List.of(Diagnostic.Code.RESTRICTED_SCRIPT), codes(bilingual, "alpha_" + GREEK_ALPHA, NAME));
        assertEquals(RestrictionLevel.HIGHLY_RESTRICTIVE, bilingual.level());
        assertEquals(List.of(Set.of(LATIN, CYRILLIC)), bilingual.permittedScripts());
        assertEquals(bilingual.perSegment(),
                IdentifierPolicy.of(RestrictionLevel.HIGHLY_RESTRICTIVE).perSegment().permitting(CYRILLIC, LATIN));
        assertNotEquals(bilingual, IdentifierPolicy.defaults());
        assertEquals("HIGHLY_RESTRICTIVE per segment permitting [" + Set.of(LATIN, CYRILLIC) + "]",
                bilingual.perSegment().toString());
    }

    @Test
    void aPolicyIsAValue() {
        assertEquals(PER_SEGMENT, IdentifierPolicy.of(RestrictionLevel.HIGHLY_RESTRICTIVE).perSegment());
        assertNotEquals(PER_SEGMENT, IdentifierPolicy.defaults());
        assertNotEquals(IdentifierPolicy.defaults(), IdentifierPolicy.defaults().withSkeletonDistinctness(false));
        assertEquals("HIGHLY_RESTRICTIVE per segment without skeleton distinctness",
                PER_SEGMENT.withSkeletonDistinctness(false).toString());
        assertEquals("HIGHLY_RESTRICTIVE", IdentifierPolicy.defaults().toString());
    }
}
