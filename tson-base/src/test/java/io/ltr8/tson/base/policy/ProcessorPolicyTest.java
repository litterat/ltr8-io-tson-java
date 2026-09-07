package io.ltr8.tson.base.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The one value a deployment states, and the one invariant it holds for every route that assembles it.
 */
class ProcessorPolicyTest {

    @Nested
    @DisplayName("a token policy cannot be per-segment, whichever route assembles it")
    class TokenPolicyIsNeverPerSegment {

        private static final UnicodePolicy PER_SEGMENT = UnicodePolicy.highlyRestrictive().perSegment();

        @Test
        void the_constructor_refuses_one() {
            assertThrows(IllegalArgumentException.class,
                    () -> ProcessorPolicy.of(UnicodePolicy.highlyRestrictive(), PER_SEGMENT,
                            LimitsPolicy.defaults()));
        }

        /**
         * The route a front door takes. It reaches the same value as the constructor, so it has to reach
         * the same refusal -- a policy assembled component-wise is not a way around a rule the named
         * setters apply.
         */
        @Test
        void deriving_one_refuses_it_too() {
            assertThrows(IllegalArgumentException.class,
                    () -> ProcessorPolicy.defaults().withTokenPolicy(PER_SEGMENT));
        }

        /** The identifier surface is where per-segment means something, and is unaffected. */
        @Test
        void the_identifier_policy_may_be_per_segment() {
            ProcessorPolicy policy = ProcessorPolicy.defaults().withIdentifierPolicy(PER_SEGMENT);
            assertEquals(PER_SEGMENT, policy.identifierPolicy());
        }

        @Test
        void the_message_says_what_to_use_instead() {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> ProcessorPolicy.defaults().withTokenPolicy(PER_SEGMENT));
            assertEquals("a token policy cannot be per-segment: '_' and '-' are ordinary characters in a "
                    + "value, not word separators -- use the whole-text policy instead", refused.getMessage());
        }
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
            assertEquals(UnicodePolicy.dataVersion(), ProcessorPolicy.defaults().unicodeDataVersion());
            assertNotNull(ProcessorPolicy.defaults().unicodeDataVersion());
        }

        @Test
        void a_default_policy_names_the_two_surfaces_defaults() {
            ProcessorPolicy defaults = ProcessorPolicy.defaults();
            assertEquals(UnicodePolicy.Level.HIGHLY_RESTRICTIVE, defaults.identifierPolicy().level());
            assertEquals(UnicodePolicy.Level.UNRESTRICTED, defaults.tokenPolicy().level());
            assertEquals(LimitsPolicy.defaults(), defaults.limits());
        }
    }
}
