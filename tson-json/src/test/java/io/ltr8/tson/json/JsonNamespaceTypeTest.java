package io.ltr8.tson.json;

import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.io.ByteSource;
import io.ltr8.tson.base.policy.ProcessorPolicy;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.json.stream.JsonStream;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A value of a namespace type is a JSON object read as the map the type describes, never as a record of its facets.
 * A field is typed by a namespace type only in a schema the meta-kernel governs, since an import stands at the
 * importer's governing level (§2.2.3).
 */
class JsonNamespaceTypeTest {

    private static final String META = "https://example.test/meta-headers.tn";

    private static final String META_TEXT = """
            !!id:"https://example.test/meta-headers.tn"
            !!import:"https://tson.io/2026/39/m/meta-kernel.tn"
            !!import:"https://tson.io/2026/39/m/meta.tn"
            !schema {
              header_name => !identifier_type { continue_add: "-" }
              headers     => namespace & { key_type: = header_name  value_type: = text }
            }
            """;

    private static final String USER_TEXT = """
            !!id:"https://example.test/request.tn"
            !!import:"https://tson.io/2026/39/m/meta-kernel.tn"
            !!import:"https://example.test/meta-headers.tn"
            !schema {
              request => { headers: headers }
            }
            """;

    private static final JsonCompiledSchema COMPILED = JsonSchemaCompiler.compile(Tson.of(ProcessorConfig.defaults()
            .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(Map.of(META, META_TEXT))))).resolve(USER_TEXT));

    private static List<Diagnostic> read(String json) {
        return read(COMPILED, "request", json);
    }

    @Test
    void anObjectIsReadAsTheMap() {
        assertEquals(List.of(), read("""
                {"headers": {"accept": "text/plain", "user-agent": "probe"}}"""));
    }

    @Test
    void aKeyIsJudgedByTheKeyType() {
        assertEquals(1, read("""
                {"headers": {"1st": "text/plain"}}""").size());
    }

    /**
     * An entry built with a namespace type is a value, not a type: the schema declaring one compiles, its types read,
     * and a document read against the entry itself is refused rather than thrown.
     */
    @Test
    void aNamespaceEntryCompilesAndTypesNothing() {
        JsonCompiledSchema governed = JsonSchemaCompiler.compile(Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(Map.of(META, META_TEXT))))).resolve("""
                !!id:"https://example.test/defaults.tn"
                !!import:"https://example.test/meta-headers.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !schema {
                  defaults => !headers { accept => "text/plain"  user-agent => "probe" }
                  greeting => text
                }
                """));

        assertEquals(List.of(), read(governed, "greeting", "\"hello\""));
        assertEquals(1, read(governed, "defaults", "{\"accept\": \"text/plain\"}").size());
    }

    private static List<Diagnostic> read(JsonCompiledSchema compiled, String type, String json) {
        List<Diagnostic> problems = new ArrayList<>();
        DiagnosticsReceiver receiver = problems::add;
        try (ByteSource bytes = ByteSource.of(json)) {
            JsonReadContext ctx = JsonReadContext.of(new JsonStream(bytes, ProcessorPolicy.defaults(), receiver),
                    receiver);
            ctx = compiled.rootDeclaration(type).map(ctx::underDeclaration).orElse(ctx);
            compiled.get(type).read(ctx);
        }
        return problems;
    }
}
