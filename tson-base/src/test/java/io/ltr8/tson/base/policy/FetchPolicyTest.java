package io.ltr8.tson.base.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The constraints a source applies obtaining a schema, and the bounds the value holds for every route that
 * assembles one.
 */
class FetchPolicyTest {

    @Nested
    @DisplayName("the bounds are the value's, whichever route assembles it")
    class Bounds {

        @Test
        void aDocumentCapMustBePositive() {
            assertThrows(IllegalArgumentException.class, () -> FetchPolicy.defaults().withMaxDocumentBytes(0));
            assertThrows(IllegalArgumentException.class, () -> FetchPolicy.defaults().withMaxDocumentBytes(-1));
        }

        /** Zero is caching disabled, which is a real answer; negative is not. */
        @Test
        void aCacheCapMayBeZeroButNotNegative() {
            assertEquals(0, FetchPolicy.defaults().withMaxCachedSchemas(0).maxCachedSchemas());
            assertThrows(IllegalArgumentException.class, () -> FetchPolicy.defaults().withMaxCachedSchemas(-1));
        }

        @Test
        void theConstructorRefusesWhatTheWithersDo() {
            assertThrows(IllegalArgumentException.class, () -> new FetchPolicy(0, 8, false));
            assertThrows(IllegalArgumentException.class, () -> new FetchPolicy(1024, -1, false));
        }
    }

    @Nested
    @DisplayName("the components stay independent")
    class Components {

        @Test
        void theDefaultsAreTheSafeOnes() {
            FetchPolicy defaults = FetchPolicy.defaults();
            assertEquals(FetchPolicy.DEFAULT_MAX_DOCUMENT_BYTES, defaults.maxDocumentBytes());
            assertEquals(FetchPolicy.DEFAULT_MAX_CACHED_SCHEMAS, defaults.maxCachedSchemas());
            assertFalse(defaults.requireContentHashPin(),
                    "a self-describing document naming a plain URL is the ordinary case");
        }

        @Test
        void changingOneLeavesTheOthersAlone() {
            FetchPolicy pinned = FetchPolicy.defaults().withRequireContentHashPin(true);

            assertTrue(pinned.requireContentHashPin());
            assertEquals(FetchPolicy.DEFAULT_MAX_DOCUMENT_BYTES, pinned.maxDocumentBytes());
            assertEquals(FetchPolicy.DEFAULT_MAX_CACHED_SCHEMAS, pinned.maxCachedSchemas());
        }

        /** It is rendered to a deployment, so it says what it admits rather than naming its own fields. */
        @Test
        void itRendersAsWhatItAdmits() {
            assertEquals("at most 4096 bytes per schema, at most 0 cached, content hash pin required",
                    new FetchPolicy(4096, 0, true).toString());
        }
    }
}
