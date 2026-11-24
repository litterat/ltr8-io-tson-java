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
class TokenPolicyTest {

    private static final String CYR_A = new String(Character.toChars(0x0430));   // а
    private static final String CYR_P = new String(Character.toChars(0x043F));   // п
    private static final String GREEK_ALPHA = new String(Character.toChars(0x03B1));
    private static final String HAN = new String(Character.toChars(0x65E5));     // 日
    private static final String DEVANAGARI = new String(Character.toChars(0x0905));

    private static void accepts(TokenPolicy policy, String text) {
        assertTrue(policy.violation(text).isEmpty(),
                () -> policy + " should accept " + text + " but said " + policy.violation(text).orElse(""));
    }

    private static void refuses(TokenPolicy policy, String text) {
        assertFalse(policy.violation(text).isEmpty(), () -> policy + " should refuse " + text);
    }

    /** The default: strictest of the practically deployable levels, and a level UTS #39 names. */
    @Test
    void highlyRestrictiveOverAWholeNameIsTheDefaultPosition() {
        TokenPolicy policy = TokenPolicy.highlyRestrictive();

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
        TokenPolicy policy = TokenPolicy.highlyRestrictive().permitting(LATIN, CYRILLIC);

        accepts(policy, "id_" + CYR_P);
        accepts(policy, CYR_A + "dmin");                      // deliberately: the deployment said so
        refuses(policy, "alpha_" + GREEK_ALPHA);              // and nothing else was widened
    }

    /** Moderately Restrictive: Latin plus one other, except the two §5.2 names. */
    @Test
    void moderatelyRestrictiveAdmitsLatinPlusOneExceptCyrillicAndGreek() {
        TokenPolicy policy = TokenPolicy.moderatelyRestrictive();

        accepts(policy, "id_" + DEVANAGARI);
        refuses(policy, "id_" + CYR_P);
        refuses(policy, "alpha_" + GREEK_ALPHA);
    }

    @Test
    void singleScriptRefusesEvenTheAugmentedSets() {
        accepts(TokenPolicy.singleScript(), "admin");
        refuses(TokenPolicy.singleScript(), HAN + HAN + "id");
    }

    @Test
    void asciiOnlyIsWhatItSays() {
        accepts(TokenPolicy.asciiOnly(), "order_id");
        refuses(TokenPolicy.asciiOnly(), "café");
    }

    /** Over a token the two "off" positions judge alike: the identifier profile level 6 drops is no token's. */
    @Test
    void theTwoOffPositionsScanNothing() {
        TokenPolicy five = TokenPolicy.scriptsUnchecked();
        TokenPolicy six = TokenPolicy.unrestricted();

        assertFalse(five.checksScripts());
        assertFalse(six.checksScripts());

        accepts(five, CYR_A + "dmin");
        accepts(six, CYR_A + "dmin");
    }
}
