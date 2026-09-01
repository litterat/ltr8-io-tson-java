package io.ltr8.tson;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonUnicodePolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The schema half of what a [TSON-DATA] §8.2 refusal states about itself, through the front door a consumer
 * actually calls ({@link Tson#validateSchema}).
 *
 * <p><b>The two ends must answer alike.</b> §8.2's name-hygiene rules run once per layer -- over declared names in
 * {@code TsonSchemaLinker}, over a Class 1 record's own field names in the schemaless readers -- and a
 * consumer holding a diagnostic should not have to know which layer produced it to learn what refused the
 * name. {@code PolicyRefusalTest} in {@code tson-compiler} asserts the data end of the same contract.
 *
 * <p>Confusable spellings are built from code points, never typed.
 */
class SchemaPolicyRefusalTest {

    /** Cyrillic а (U+0430). */
    private static final String CYR_A = new String(Character.toChars(0x0430));

    /** The three codes that are a §8.2 refusal rather than a verdict, one per rule. */
    private static boolean isRefusal(Diagnostic diagnostic) {
        return diagnostic.code() == Diagnostic.Code.CONFUSABLE_NAMES
                || diagnostic.code() == Diagnostic.Code.RESTRICTED_CHARACTER
                || diagnostic.code() == Diagnostic.Code.RESTRICTED_SCRIPT;
    }

    private static List<Diagnostic> refusals(String declarations) {
        List<Diagnostic> problems = Tson.builder().build().validateSchema("""
                !!id:"https://example.test/refusal.tn"
                !!meta:"https://tson.io/2026/34/m/meta.tn"
                !!import:"https://tson.io/2026/34/m/core.tn"
                {
                %s
                }
                """.formatted(declarations));

        problems.forEach(problem -> assertTrue(isRefusal(problem),
                () -> "§8.2's refusal is not one of §8.1's four categories: " + problems));
        return problems;
    }

    private static Diagnostic soleRefusal(String declarations) {
        List<Diagnostic> problems = refusals(declarations);
        assertEquals(1, problems.size(), problems::toString);
        return problems.getFirst();
    }

    /**
     * The look-alike rule over the declared names of one schema -- where a spoofed name changes which type a
     * document validates against.
     *
     * <p>{@code comer}/{@code corner} rather than a homograph, and deliberately: it is §8.2's own pure-ASCII
     * example (the two share a skeleton through {@code m → rn}), so it is single-script and inside the
     * identifier profile, so the other two rules have nothing to say about it. That is what isolates the
     * look-alike rule -- and it is also the case that shows why a refusal is not validity, since these are
     * two perfectly ordinary English words.
     */
    @Test
    void twoDeclaredNamesThatReadAlikeAreConfusableNames() {
        assertEquals(Diagnostic.Code.CONFUSABLE_NAMES,
                soleRefusal("  comer => text\n  corner => text").code());
    }

    /**
     * <b>One name, two rules</b> -- and the case that shows why the three codes have to be three. A
     * Cyrillic-{@code а} {@code admin} trips both the look-alike rule (it reads as the {@code admin} beside
     * it) and the restricted-script rule (it mixes scripts inside one word), and the two want different fixes:
     * rename one of the
     * pair, or admit the script combination. A consumer seeing both learns from the codes alone which is the
     * one a policy relaxation would silence.
     */
    @Test
    void aHomographTripsTwoRulesAndEachSaysWhichItWas() {
        List<Diagnostic> problems = refusals("  admin => text\n  " + CYR_A + "dmin => text");

        assertEquals(List.of(Diagnostic.Code.CONFUSABLE_NAMES, Diagnostic.Code.RESTRICTED_SCRIPT),
                problems.stream().map(Diagnostic::code).toList(), problems::toString);
    }

    /** The restricted-character rule at a declared name. */
    @Test
    void aRestrictedCharacterInADeclaredNameIsItsOwnCode() {
        assertEquals(Diagnostic.Code.RESTRICTED_CHARACTER,
                soleRefusal("  a" + new String(Character.toChars(0x0132)) + "b => text").code());
    }

    /** The restricted-script rule at a declared name, under §8.2's recommended default level. */
    @Test
    void aMixedScriptDeclaredNameIsRestrictedScript() {
        assertEquals(Diagnostic.Code.RESTRICTED_SCRIPT, soleRefusal("  p" + CYR_A + "y => text").code());
    }

    /**
     * <b>What refused a declared name is stated by the instance, not by the diagnostic</b> -- the same answer
     * the data end gives ({@code PolicyRefusalTest}), from the same place. §8.2 requires a refusal to be
     * reported under a stated policy and a stated data version, and both are properties of this processor:
     * constant across every refusal a run produces, and needed by whoever writes the next schema *before*
     * they write it, which a component that only appears on a failure cannot supply.
     */
    @Test
    void theInstanceStatesThePolicyThatRefusedTheName() {
        Tson tson = Tson.builder().identifierPolicy(TsonUnicodePolicy.asciiOnly()).build();

        assertEquals(Diagnostic.Code.RESTRICTED_SCRIPT, tson.validateSchema("""
                !!id:"https://example.test/refusal-policy.tn"
                !!meta:"https://tson.io/2026/34/m/meta.tn"
                !!import:"https://tson.io/2026/34/m/core.tn"
                { p%sy => text }
                """.formatted(CYR_A)).getFirst().code());

        assertEquals(TsonUnicodePolicy.Level.ASCII_ONLY,
                tson.processorPolicy().identifierPolicy().level());
        assertEquals(TsonUnicodePolicy.dataVersion(), tson.processorPolicy().unicodeDataVersion());
    }
}
