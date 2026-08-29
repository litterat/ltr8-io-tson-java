package io.ltr8.tson;

import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TsonConfig#httpSchemas} and {@link TsonConfig#fileSchemas} -- the short forms of the two sources
 * this library ships, alongside {@link TsonConfig#schemaSource} rather than instead of it.
 */
class SchemaSourceConfigTest {

    private static final String HOST = "schemas.example.test";
    private static final String SCHEMA_URI = "https://" + HOST + "/order-1.tn";

    private static final String SCHEMA = """
            !!id:"%s"
            !!meta:"https://tson.io/2026/34/m/meta.tn"
            !!import:"https://tson.io/2026/34/m/core.tn"
            {
              order => { sku: text  quantity: int32 }
            }
            """.formatted(SCHEMA_URI);

    private static final String DOCUMENT = """
            !!schema:"%s"
            !order { sku: "ABC-1"  quantity: 3 }""".formatted(SCHEMA_URI);

    /** The whole point of the short form: one call where the long form is a builder and a wiring step. */
    @Test
    void fileSchemasServesADocumentEndToEnd(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("order-1.tn"), SCHEMA);

        Tson tson = Tson.builder().fileSchemas(HOST, dir).build();
        TsonValue order = tson.treeReader().read(DOCUMENT);

        assertEquals("ABC-1", order.get("sku").asString().orElseThrow());
    }

    /** Repeatable: each call maps another host into the one source, rather than replacing the last. */
    @Test
    void fileSchemasAccumulatesHosts(@TempDir Path dir) throws IOException {
        Path first = Files.createDirectory(dir.resolve("first"));
        Path second = Files.createDirectory(dir.resolve("second"));
        Files.writeString(first.resolve("order-1.tn"), SCHEMA);
        String otherUri = "https://other.example.test/thing-1.tn";
        Files.writeString(second.resolve("thing-1.tn"), SCHEMA.replace(SCHEMA_URI, otherUri));

        Tson tson = Tson.builder()
                .fileSchemas(HOST, first)
                .fileSchemas("other.example.test", second)
                .build();

        assertEquals("ABC-1", tson.treeReader().read(DOCUMENT).get("sku").asString().orElseThrow());
        assertEquals(List.of(), tson.validate(DOCUMENT.replace(SCHEMA_URI, otherUri)));
    }

    /** Deny by default survives the short form -- a host not named is not served, and says so as a diagnostic. */
    @Test
    void anUnnamedHostIsNotServed(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("order-1.tn"), SCHEMA);
        Tson tson = Tson.builder().fileSchemas("elsewhere.example.test", dir).build();

        assertTrue(tson.validate(DOCUMENT).stream()
                .anyMatch(d -> d.message().contains("is not one of")), () -> tson.validate(DOCUMENT).toString());
    }

    /** `httpSchemas` allows a host without fetching anything, so this needs no server to be worth asserting. */
    @Test
    void httpSchemasAllowsOnlyTheHostsItNames() {
        Tson tson = Tson.builder().httpSchemas("allowed.example.test").build();

        assertTrue(tson.validate("""
                !!schema:"https://denied.example.test/order-1.tn"
                !order { }""").stream().anyMatch(d -> d.message().contains("is not one of")));
    }

    /**
     * The three ways of naming a source are mutually exclusive, on the precedent {@code bindings} and
     * {@code dataBindContext} already set: each builds one source, and {@code schemaSource} holds one, so
     * mixing them would silently drop one rather than compose them.
     */
    @Test
    void theShortFormsAndTheGeneralSeamAreMutuallyExclusive(@TempDir Path dir) {
        assertTrue(assertThrows(IllegalStateException.class,
                () -> Tson.builder().httpSchemas("a.example.test").fileSchemas(HOST, dir))
                .getMessage().contains("not both"));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> Tson.builder().fileSchemas(HOST, dir).httpSchemas("a.example.test"))
                .getMessage().contains("not both"));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> Tson.builder().schemaSource(uri -> SCHEMA).httpSchemas("a.example.test"))
                .getMessage().contains("not both"));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> Tson.builder().fileSchemas(HOST, dir).schemaSource(uri -> SCHEMA))
                .getMessage().contains("not both"));
    }
}
