package io.ltr8.tson.compiler.lexer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * UTS #39 §4's {@code skeleton()}: two names are confusable exactly when their skeletons are equal.
 *
 * <p>The pairs below are built from code points rather than typed, because the whole point of a confusable
 * is that the two spellings are indistinguishable in an editor -- a test written with literals would be
 * unreviewable and one careless normalisation away from testing nothing.
 */
class ConfusablesTest {

    private static String cp(int... points) {
        StringBuilder sb = new StringBuilder();
        for (int p : points) {
            sb.appendCodePoint(p);
        }
        return sb.toString();
    }

    private static void confusable(String a, String b) {
        assertEquals(Confusables.skeleton(a), Confusables.skeleton(b),
                () -> "expected confusable: " + a + " / " + b);
    }

    private static void distinct(String a, String b) {
        assertNotEquals(Confusables.skeleton(a), Confusables.skeleton(b),
                () -> "expected distinct: " + a + " / " + b);
    }

    /** The mixed-script homograph §9.4 opens with: Cyrillic а (U+0430) against Latin a. */
    @Test
    void aCyrillicHomographIsConfusableWithItsLatinSpelling() {
        confusable("admin", cp(0x0430) + "dmin");
        confusable("data", "d" + cp(0x0430) + "ta");
    }

    /** The whole-script case a restriction level cannot see: every character Cyrillic, reading as Latin. */
    @Test
    void aWholeScriptConfusableIsCaught() {
        confusable("aec", cp(0x0430, 0x0435, 0x0441));
    }

    /** Greek upsilon for Latin u, and the digit/letter pairs any font conflates. */
    @Test
    void otherClassicPairsAreCaught() {
        confusable("user", cp(0x03C5) + "ser");
        confusable("l", "I");
        confusable("O", "0");
        confusable("rn", "m");
    }

    /**
     * And the precision that makes this usable as a rule rather than a heuristic: names a real schema
     * declares together do not collide. A rule that fired on these would be switched off.
     */
    @Test
    void ordinaryNamesDeclaredTogetherDoNotCollide() {
        String[] names = {"order", "order_id", "customer", "customer_id", "total", "subtotal",
                          "created_at", "updated_at", "name", "email", "address", "addresses",
                          "price", "prices", "item", "items", "status", "state", "type", "kind",
                          "id_" + cp(0x043F, 0x043E, 0x043B), "url_" + cp(0x0430, 0x0434, 0x0440)};
        for (int i = 0; i < names.length; i++) {
            for (int j = i + 1; j < names.length; j++) {
                distinct(names[i], names[j]);
            }
        }
    }

    /** A name is confusable with itself, whatever normalisation form it arrived in. */
    @Test
    void normalisationDoesNotAffectTheSkeleton() {
        confusable("caf" + cp(0x00E9), "cafe" + cp(0x0301));
    }
}
