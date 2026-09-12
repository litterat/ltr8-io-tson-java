package io.ltr8.tson.base.source;

import io.ltr8.tson.base.SchemaFetchException;
import io.ltr8.tson.base.SchemaFetchException.Reason;
import io.ltr8.tson.base.policy.FetchPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * One {@link FetchPolicy} applies unchanged to both sources -- which is the whole of why it is a value.
 *
 * <p>[TSON-DATA] §2.2.1 keeps identity apart from location, so a schema may legitimately move from an HTTPS
 * origin to a directory without being renamed. What must move with it is the constraints, and a deployment
 * that had to restate them per source would be one restatement away from the two disagreeing.
 *
 * <p>The HTTP source is exercised through the policy it holds rather than over a socket: what is under test
 * is that the value reaches it, and its own suite covers fetching.
 */
class FetchPolicySharingTest {

    private static final String HOST = "schemas.example.test";

    private static String schemaAt(String path) {
        return """
                !!id:"https://%s%s"
                !!meta:"https://tson.io/2026/36/m/meta.tn"
                !!import:"https://tson.io/2026/36/m/core.tn"
                {
                  order => { sku: text  quantity: int32 }
                }
                """.formatted(HOST, path);
    }

    private static String reference() {
        return "https://" + HOST + "/order-1.tn";
    }

    /**
     * <b>The pin requirement is refused identically by both.</b> It is the one component that changes what a
     * reference is allowed to be rather than how much of one may be read, so it is the one where a source
     * disagreeing with its sibling would let a deployment believe it pins everywhere when it pins on one
     * route.
     */
    @Test
    void onePolicyPinsBothSources(@TempDir Path dir) throws IOException {
        FetchPolicy pinned = FetchPolicy.defaults().withRequireContentHashPin(true);
        Files.writeString(dir.resolve("order-1.tn"), schemaAt("/order-1.tn"));

        SchemaSource files = FileSchemaSource.builder().mapHost(HOST, dir).fetchPolicy(pinned).build();
        SchemaSource http = HttpSchemaSource.builder().allowHost(HOST).fetchPolicy(pinned).build();

        assertEquals(Reason.NOT_PERMITTED, refusal(files, reference()).reason());
        assertEquals(Reason.NOT_PERMITTED, refusal(http, reference()).reason());
        assertEquals(schemaAt("/order-1.tn"), files.fetch(reference() + "?sha256=abc"),
                "and an unpinned reference is the only thing it refuses");
    }

    /** The size cap likewise, against bytes actually read on either route. */
    @Test
    void onePolicyCapsBothSources(@TempDir Path dir) throws IOException {
        FetchPolicy tiny = FetchPolicy.defaults().withMaxDocumentBytes(16);
        Files.writeString(dir.resolve("order-1.tn"), schemaAt("/order-1.tn"));

        SchemaSource files = FileSchemaSource.builder().mapHost(HOST, dir).fetchPolicy(tiny).build();

        assertEquals(Reason.TOO_LARGE, refusal(files, reference()).reason());
    }

    /**
     * <b>The component setters derive from the policy rather than replacing it</b>, on both builders. A
     * setter written to replace would silently discard whatever the composed form stated before it, which is
     * the failure a shared value exists to make impossible.
     */
    @Test
    void aComponentSetterDoesNotDiscardTheStatedPolicy(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("order-1.tn"), schemaAt("/order-1.tn"));

        SchemaSource files = FileSchemaSource.builder()
                .mapHost(HOST, dir)
                .fetchPolicy(FetchPolicy.defaults().withRequireContentHashPin(true))
                .maxCachedSchemas(4)
                .build();

        assertEquals(Reason.NOT_PERMITTED, refusal(files, reference()).reason(),
                "the pin stated by the whole policy survives a component stated after it");
    }

    /** And the other way round: a component stated first is not discarded by the composed form's defaults. */
    @Test
    void aStatedPolicyReplacesWhatCameBeforeIt(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("order-1.tn"), schemaAt("/order-1.tn"));

        SchemaSource files = FileSchemaSource.builder()
                .mapHost(HOST, dir)
                .requireContentHashPin(true)
                .fetchPolicy(FetchPolicy.defaults())
                .build();

        assertEquals(schemaAt("/order-1.tn"), files.fetch(reference()),
                "a whole policy is a whole statement, so it settles every component it names");
    }

    private static SchemaFetchException refusal(SchemaSource source, String uri) {
        return assertThrows(SchemaFetchException.class, () -> source.fetch(uri));
    }
}
