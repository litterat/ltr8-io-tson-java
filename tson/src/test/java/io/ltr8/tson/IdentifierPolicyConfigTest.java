package io.ltr8.tson;

import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.compiler.TsonUnicodePolicy;
import io.ltr8.tson.schema.TsonSchemaValidationException;
import org.junit.jupiter.api.Test;

import static java.lang.Character.UnicodeScript.CYRILLIC;
import static java.lang.Character.UnicodeScript.LATIN;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TsonConfig#identifierPolicy} end to end: the level reaches the linker, and each rung of the ladder
 * changes what a schema may declare ({@code SPEC-FEEDBACK.md} #3 Step 4).
 *
 * <p>Mixed-script names are built from code points rather than typed — the subject is spellings that look
 * alike, so a literal would be unreviewable.
 */
class IdentifierPolicyConfigTest {

    private static final String CYR_P = new String(Character.toChars(0x043F));   // п
    private static final String CYR_A = new String(Character.toChars(0x0430));   // а

    /** A schema whose one field name is an ordinary compound: a Latin abbreviation beside a Cyrillic word. */
    private static final String COMPOUND = schema("id_" + CYR_P);

    /** The same shape, but mixing scripts *inside* one word — how a homograph reads as another name. */
    private static final String HOMOGRAPH = schema(CYR_A + "dmin");

    private static String schema(String fieldName) {
        return """
                !!id:"https://example.test/policy.tn"
                !!meta:"https://tson.io/2026/34/m/meta.tn"
                !!import:"https://tson.io/2026/34/m/core.tn"
                { rec => { %s: text } }
                """.formatted(fieldName);
    }

    private static void accepts(TsonUnicodePolicy policy, String schema) {
        TsonSchemaSource source = uri -> schema;
        Tson tson = (policy == null ? Tson.builder() : Tson.builder().identifierPolicy(policy))
                .schemaSource(source).build();
        assertNotNull(tson.resolve(schema));
    }

    private static String refuses(TsonUnicodePolicy policy, String schema) {
        TsonSchemaSource source = uri -> schema;
        Tson tson = (policy == null ? Tson.builder() : Tson.builder().identifierPolicy(policy))
                .schemaSource(source).build();
        return assertThrows(TsonSchemaValidationException.class, () -> tson.resolve(schema)).getMessage();
    }

    /**
     * The default is Highly Restrictive over a whole name, and it refuses an ordinary compound. That is the
     * cost of the default, pinned here rather than left to be discovered: the relaxation is the unit.
     */
    @Test
    void theDefaultRefusesAMixedScriptCompound() {
        assertTrue(refuses(null, COMPOUND).contains("mixes the scripts"));
    }

    /** The first relaxation to reach for: same level, applied per segment. */
    @Test
    void perSegmentAdmitsTheCompoundAndStillRefusesTheHomograph() {
        accepts(TsonUnicodePolicy.highlyRestrictive().perSegment(), COMPOUND);
        assertTrue(refuses(TsonUnicodePolicy.highlyRestrictive().perSegment(), HOMOGRAPH)
                .contains("mixes the scripts"));
    }

    /** Narrower still: name the combination rather than change the shape of the rule. */
    @Test
    void anExplicitlyPermittedCombinationAdmitsOnlyThat() {
        accepts(TsonUnicodePolicy.highlyRestrictive().permitting(LATIN, CYRILLIC), COMPOUND);
    }

    /** And the off positions reach the linker like any other rung. */
    @Test
    void scriptsUncheckedTurnsTheRuleOff() {
        accepts(TsonUnicodePolicy.scriptsUnchecked(), COMPOUND);
        accepts(TsonUnicodePolicy.scriptsUnchecked(), HOMOGRAPH);
    }

    /** An all-Latin schema is unaffected at every rung, which is what keeps the default deployable. */
    @Test
    void anOrdinarySchemaIsUnaffected() {
        for (TsonUnicodePolicy policy : new TsonUnicodePolicy[] {null, TsonUnicodePolicy.highlyRestrictive(),
                TsonUnicodePolicy.highlyRestrictive().perSegment(), TsonUnicodePolicy.asciiOnly()}) {
            accepts(policy, schema("order_id"));
        }
    }
}
