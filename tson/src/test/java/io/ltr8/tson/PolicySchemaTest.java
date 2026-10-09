package io.ltr8.tson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.ltr8.unicode.RestrictionLevel;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.meta.EnumBody;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code policy.tn}, the processor policy's vocabulary ([TSON-DATA] §8.2, §9.1), against the code that applies it:
 * {@code restriction_level} is UTS #39 §5.2's six levels, which {@link RestrictionLevel} also states, and the
 * two must list the same members in the same order, so a report and the configuration that produced it agree.
 */
class PolicySchemaTest {

    @Test
    void restrictionLevelMirrorsScriptPolicyLevel() {
        EnumBody declared = assertInstanceOf(EnumBody.class, Tson.standard().bindRegistry().core()
                .resolveLinked(TsonBundledSchemas.POLICY_ID).schema().entries().get("restriction_level").body());

        assertEquals(Arrays.stream(RestrictionLevel.values()).map(Enum::name).toList(), declared.members());
    }

    /** A deployment's policy document states no data version: the tables are the processor's, not its choice. */
    @Test
    void aDeploymentsPolicyDocumentValidates() {
        assertEquals(List.of(), Tson.standard().validate("""
                !!schema:"https://tson.io/2026/38/m/policy.tn"
                !policy {
                  identifier_policy: { level: HIGHLY_RESTRICTIVE  per_segment: false
                                       skeleton_distinctness: true  permitting: [] }
                  token_policy: { level: UNRESTRICTED  permitting: [] }
                  limits: { max_depth: 64 }
                }
                """));
    }
}
