package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.TsonConfig;
import io.ltr8.tson.base.policy.FetchPolicy;
import io.ltr8.tson.base.source.FileSchemaSource;
import io.ltr8.tson.base.source.SchemaAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link TsonConfig#schemaAccess} -- where a {@link Tson} may obtain a schema, and under what constraints.
 *
 * <p>{@link TsonConfig#withProcessorPolicy}'s sibling: the same kind of statement, reaching a different
 * subsystem. What is pinned here is the front door's half -- that an access reaches the loader and that the
 * {@link FetchPolicy} inside it governs what the loader obtains. How an access is *assembled*, and which
 * combinations are refused, is {@link SchemaAccess}'s own and is pinned by {@code SchemaAccessTest}: the
 * whole point of the value is that those rules are stated once rather than once per front door.
 */
class SchemaAccessConfigTest {

    private static final String HOST = "schemas.example.test";
    private static final String SCHEMA_URI = "https://" + HOST + "/order-1.tn";

    private static final String SCHEMA = """
            !!id:"%s"
            !!meta:"https://tson.io/2026/35/m/meta.tn"
            !!import:"https://tson.io/2026/35/m/core.tn"
            {
              order => { sku: text  quantity: int32 }
            }
            """.formatted(SCHEMA_URI);

    private static final String DOCUMENT = """
            !!schema:"%s"
            !order { sku: "ABC-1"  quantity: 3 }""".formatted(SCHEMA_URI);

    /** The one-call form reaches the loader, which is the baseline everything below varies from. */
    @Test
    void anAccessReachesTheLoader(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("order-1.tn"), SCHEMA);

        Tson tson = Tson.of(TsonConfig.defaults().withSchemaAccess(SchemaAccess.fileSchemas(HOST, dir)));

        assertEquals("ABC-1", tson.treeReader().read(DOCUMENT).get("sku").asString().orElseThrow());
    }

    /** And the default is deny: an unconfigured instance fetches nothing beyond the standard library. */
    @Test
    void theDefaultAccessFetchesNothing() {
        assertEquals(List.of(Diagnostic.Code.SCHEMA_NOT_PERMITTED),
                Tson.standard().validate(DOCUMENT).stream().map(Diagnostic::code).toList());
    }

    /**
     * <b>The policy inside an access governs what the loader obtains.</b> This is the half that would break
     * silently if the pair were handed to the front door separately -- a source would arrive with its bounds
     * left behind, and nothing would say so.
     *
     * <p>A refused fetch is also not a verdict: the read reports one of the five {@code SCHEMA_*} codes
     * rather than calling the document invalid, nothing about it having been judged.
     */
    @Test
    void theCapInsideAnAccessGovernsTheLoader(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("order-1.tn"), SCHEMA);

        Tson tson = Tson.of(TsonConfig.defaults()
                .withSchemaAccess(SchemaAccess.builder()
                        .fetchPolicy(FetchPolicy.defaults().withMaxDocumentBytes(16))
                        .fileSchemas(HOST, dir)
                        .build()));
        List<Diagnostic> problems = tson.validate(DOCUMENT);

        assertEquals(List.of(Diagnostic.Code.SCHEMA_TOO_LARGE),
                problems.stream().map(Diagnostic::code).toList(), problems::toString);
        assertEquals(List.of(false), problems.stream().map(d -> d.code().verdict()).toList(),
                "a schema that could not be obtained says nothing about the document");
    }

    /** The pin requirement likewise, which is the component the one-call form cannot express. */
    @Test
    void thePinRequirementInsideAnAccessGovernsTheLoader(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("order-1.tn"), SCHEMA);

        Tson tson = Tson.of(TsonConfig.defaults()
                .withSchemaAccess(SchemaAccess.builder()
                        .fetchPolicy(FetchPolicy.defaults().withRequireContentHashPin(true))
                        .fileSchemas(HOST, dir)
                        .build()));

        assertEquals(List.of(Diagnostic.Code.SCHEMA_NOT_PERMITTED),
                tson.validate(DOCUMENT).stream().map(Diagnostic::code).toList());
    }

    /** A source of the caller's own reaches the loader as given, its own builder having configured it. */
    @Test
    void aSourceOfYourOwnReachesTheLoader(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("order-1.tn"), SCHEMA);

        Tson tson = Tson.of(TsonConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(FileSchemaSource.builder().mapHost(HOST, dir).build())));

        assertEquals("ABC-1", tson.treeReader().read(DOCUMENT).get("sku").asString().orElseThrow());
    }
}
