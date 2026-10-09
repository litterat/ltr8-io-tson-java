package io.ltr8.tson.base.policy;

import io.ltr8.unicode.RestrictionLevel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The one value a deployment states, and the one invariant it holds for every route that assembles it.
 */
class ProcessorPolicyTest {

    /** The unit is the identifier policy's, where it means something; a token policy has none to state. */
    @Test
    void the_identifier_policy_may_be_per_segment() {
        IdentifierPolicy perSegment = IdentifierPolicy.of(ScriptPolicy.highlyRestrictive()).perSegment();
        ProcessorPolicy policy = ProcessorPolicy.defaults().withIdentifierPolicy(perSegment);
        assertEquals(perSegment, policy.identifierPolicy());
    }

    @Nested
    @DisplayName("the components stay independent")
    class Components {

        @Test
        void changing_one_leaves_the_other_two_alone() {
            ProcessorPolicy raised = ProcessorPolicy.defaults()
                    .withLimits(LimitsPolicy.defaults().withMaxDepth(16));
            assertEquals(16, raised.limits().maxDepth());
            assertEquals(ProcessorPolicy.defaults().identifierPolicy(), raised.identifierPolicy());
            assertEquals(ProcessorPolicy.defaults().tokenPolicy(), raised.tokenPolicy());
        }

        /** Not a choice, so not a parameter: it is a property of the tables this build carries. */
        @Test
        void the_data_version_is_the_builds_own() {
            assertEquals(ProcessorPolicy.dataVersion(), ProcessorPolicy.defaults().unicodeDataVersion());
            assertNotNull(ProcessorPolicy.defaults().unicodeDataVersion());
        }

        @Test
        void a_default_policy_names_the_two_surfaces_defaults() {
            ProcessorPolicy defaults = ProcessorPolicy.defaults();
            assertEquals(IdentifierPolicy.defaults(), defaults.identifierPolicy());
            assertEquals(RestrictionLevel.UNRESTRICTED, defaults.tokenPolicy().level());
            assertEquals(LimitsPolicy.defaults(), defaults.limits());
        }
    }
}
