package io.ltr8.tson.base.source;

import io.ltr8.tson.base.SchemaFetchException;
import io.ltr8.tson.base.SchemaFetchException.Reason;
import io.ltr8.tson.base.policy.FetchPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SchemaAccess} -- the source and the policy governing it, as one value.
 */
class SchemaAccessTest {

    private static final String HOST = "schemas.example.test";

    private static String schema() {
        return """
                !!id:"https://%s/order-1.tn"
                !!meta:"https://tson.io/2026/36/m/meta.tn"
                !!import:"https://tson.io/2026/36/m/core.tn"
                {
                  order => { sku: text  quantity: int32 }
                }
                """.formatted(HOST);
    }

    private static String reference() {
        return "https://" + HOST + "/order-1.tn";
    }

    /** Repeatable: each call maps another host into the one source rather than replacing the last. */
    @Test
    void fileSchemasAccumulatesHosts(@TempDir Path dir) throws IOException {
        Path first = Files.createDirectory(dir.resolve("first"));
        Path second = Files.createDirectory(dir.resolve("second"));
        Files.writeString(first.resolve("order-1.tn"), schema());
        Files.writeString(second.resolve("order-1.tn"), schema());

        SchemaSource source = SchemaAccess.builder()
                .fileSchemas(HOST, first)
                .fileSchemas("other.example.test", second)
                .build()
                .source();

        assertEquals(schema(), source.fetch(reference()));
        assertEquals(schema(), source.fetch("https://other.example.test/order-1.tn"));
    }

    @Nested
    @DisplayName("the policy governs the source stated beside it")
    class PolicyReachesTheSource {

        @Test
        void aPolicyStatedHereReachesTheShortFormsSource(@TempDir Path dir) throws IOException {
            Files.writeString(dir.resolve("order-1.tn"), schema());

            SchemaAccess access = SchemaAccess.builder()
                    .fetchPolicy(FetchPolicy.defaults().withRequireContentHashPin(true))
                    .fileSchemas(HOST, dir)
                    .build();

            assertEquals(Reason.NOT_PERMITTED,
                    assertThrows(SchemaFetchException.class, () -> access.source().fetch(reference())).reason());
            assertTrue(access.fetchPolicy().requireContentHashPin(), "and the value reports what it applied");
        }

        /** Order does not matter: the policy is applied when the source is built, not when it is named. */
        @Test
        void aPolicyStatedAfterTheHostReachesItToo(@TempDir Path dir) throws IOException {
            Files.writeString(dir.resolve("order-1.tn"), schema());

            SchemaAccess access = SchemaAccess.builder()
                    .fileSchemas(HOST, dir)
                    .fetchPolicy(FetchPolicy.defaults().withMaxDocumentBytes(16))
                    .build();

            assertEquals(Reason.TOO_LARGE,
                    assertThrows(SchemaFetchException.class, () -> access.source().fetch(reference())).reason());
        }
    }

    @Nested
    @DisplayName("deny by default")
    class Defaults {

        @Test
        void anUnconfiguredAccessFetchesNothing() {
            assertEquals(Reason.NOT_PERMITTED, assertThrows(SchemaFetchException.class,
                    () -> SchemaAccess.builder().build().source().fetch(reference())).reason());
            assertEquals(Reason.NOT_PERMITTED, assertThrows(SchemaFetchException.class,
                    () -> SchemaAccess.registeredOnly().source().fetch(reference())).reason());
        }

        @Test
        void anUnconfiguredAccessCarriesTheDefaultPolicy() {
            assertEquals(FetchPolicy.defaults(), SchemaAccess.builder().build().fetchPolicy());
        }
    }

    @Nested
    @DisplayName("the ways of naming a source are mutually exclusive")
    class MutualExclusion {

        @Test
        void aSourceOfYourOwnIsKeptAsGiven(@TempDir Path dir) {
            SchemaSource own = FileSchemaSource.builder().mapHost(HOST, dir).build();

            assertSame(own, SchemaAccess.of(own).source());
            assertSame(own, SchemaAccess.builder().source(own).build().source());
        }

        @Test
        void theTwoShortFormsMayNotBeMixed(@TempDir Path dir) {
            assertThrows(IllegalStateException.class,
                    () -> SchemaAccess.builder().httpSchemas(HOST).fileSchemas(HOST, dir));
            assertThrows(IllegalStateException.class,
                    () -> SchemaAccess.builder().fileSchemas(HOST, dir).httpSchemas(HOST));
        }

        @Test
        void aShortFormMayNotBeMixedWithASourceOfYourOwn(@TempDir Path dir) {
            SchemaSource own = FileSchemaSource.builder().mapHost(HOST, dir).build();

            assertThrows(IllegalStateException.class,
                    () -> SchemaAccess.builder().source(own).httpSchemas(HOST));
            assertThrows(IllegalStateException.class,
                    () -> SchemaAccess.builder().httpSchemas(HOST).source(own));
        }

        /**
         * <b>A policy beside a source of your own is refused, in either order.</b> That source was configured
         * at its own builder and this has no way to reach inside it, so applying the policy is impossible and
         * ignoring it silently is worse than saying so.
         */
        @Test
        void aPolicyMayNotBeStatedBesideASourceOfYourOwn(@TempDir Path dir) {
            SchemaSource own = FileSchemaSource.builder().mapHost(HOST, dir).build();
            FetchPolicy pinned = FetchPolicy.defaults().withRequireContentHashPin(true);

            assertThrows(IllegalStateException.class,
                    () -> SchemaAccess.builder().source(own).fetchPolicy(pinned));
            assertThrows(IllegalStateException.class,
                    () -> SchemaAccess.builder().fetchPolicy(pinned).source(own));
        }

        /** And {@link SchemaAccess#of} reports the default rather than claiming to govern what it cannot. */
        @Test
        void anAccessOverAGivenSourceReportsNoPolicyOfItsOwn(@TempDir Path dir) {
            SchemaSource own = FileSchemaSource.builder().mapHost(HOST, dir).build();

            assertEquals(FetchPolicy.defaults(), SchemaAccess.of(own).fetchPolicy());
        }
    }
}
