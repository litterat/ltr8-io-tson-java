package io.ltr8.tson.base.policy;

import org.junit.jupiter.api.Test;

import static java.lang.Character.UnicodeScript.CYRILLIC;
import static java.lang.Character.UnicodeScript.LATIN;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UTS #39 §5.2's restriction levels over whole text. The unit is {@link IdentifierPolicy}'s
 * ({@code IdentifierPolicyTest}).
 *
 * <p>Mixed-script names are built from code points, never typed: the whole subject is spellings that look
 * alike, so a literal would be unreviewable.
 */
class ScriptPolicyTest {

    private static final String CYR_A = new String(Character.toChars(0x0430));   // а
    private static final String CYR_P = new String(Character.toChars(0x043F));   // п
    private static final String GREEK_ALPHA = new String(Character.toChars(0x03B1));
    private static final String HAN = new String(Character.toChars(0x65E5));     // 日
    private static final String DEVANAGARI = new String(Character.toChars(0x0905));

    private static void accepts(ScriptPolicy policy, String text) {
        assertTrue(policy.violation(text).isEmpty(),
                () -> policy + " should accept " + text + " but said " + policy.violation(text).orElse(""));
    }

    private static void refuses(ScriptPolicy policy, String text) {
        assertFalse(policy.violation(text).isEmpty(), () -> policy + " should refuse " + text);
    }

    /** The default: strictest of the practically deployable levels, and a level UTS #39 names. */
    @Test
    void highlyRestrictiveOverAWholeNameIsTheDefaultPosition() {
        ScriptPolicy policy = ScriptPolicy.highlyRestrictive();

        accepts(policy, "admin");
        accepts(policy, "пользователь");
        accepts(policy, HAN + HAN + "id");                    // Latin + Han, the Jpan augmented set
        refuses(policy, CYR_A + "dmin");                      // the homograph
        refuses(policy, "id_" + CYR_P);                       // and an ordinary compound, which is the cost
        refuses(policy, "alpha_" + GREEK_ALPHA);
    }

    /** The narrowest relaxation: name the combination instead of dropping a level. */
    @Test
    void anAdditionalPermittedSetAdmitsOnlyThatCombination() {
        ScriptPolicy policy = ScriptPolicy.highlyRestrictive().permitting(LATIN, CYRILLIC);

        accepts(policy, "id_" + CYR_P);
        accepts(policy, CYR_A + "dmin");                      // deliberately: the deployment said so
        refuses(policy, "alpha_" + GREEK_ALPHA);              // and nothing else was widened
    }

    /** Moderately Restrictive: Latin plus one other, except the two §5.2 names. */
    @Test
    void moderatelyRestrictiveAdmitsLatinPlusOneExceptCyrillicAndGreek() {
        ScriptPolicy policy = ScriptPolicy.moderatelyRestrictive();

        accepts(policy, "id_" + DEVANAGARI);
        refuses(policy, "id_" + CYR_P);
        refuses(policy, "alpha_" + GREEK_ALPHA);
    }

    @Test
    void singleScriptRefusesEvenTheAugmentedSets() {
        accepts(ScriptPolicy.singleScript(), "admin");
        refuses(ScriptPolicy.singleScript(), HAN + HAN + "id");
    }

    @Test
    void asciiOnlyIsWhatItSays() {
        accepts(ScriptPolicy.asciiOnly(), "order_id");
        refuses(ScriptPolicy.asciiOnly(), "café");
    }

    /**
     * The two "off" positions differ, and only on whether the identifier profile survives. §5.2 makes level 6
     * the one that drops it; a deployment meaning "stop checking scripts" wants level 5.
     */
    @Test
    void theTwoOffPositionsDifferOnlyInTheIdentifierProfile() {
        ScriptPolicy five = ScriptPolicy.scriptsUnchecked();
        ScriptPolicy six = ScriptPolicy.unrestricted();

        assertFalse(five.checksScripts());
        assertFalse(six.checksScripts());
        assertTrue(five.appliesIdentifierProfile(), "level 5 keeps the identifier profile");
        assertFalse(six.appliesIdentifierProfile(), "level 6 drops it -- §5.2 says so");

        accepts(five, CYR_A + "dmin");
        accepts(six, CYR_A + "dmin");
    }
}
