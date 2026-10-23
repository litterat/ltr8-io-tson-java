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

/** A value of a namespace type is a JSON object read as the map the type describes, never as a record of its facets. */
class JsonNamespaceTypeTest {

    private static final String META = "https://example.test/meta-headers.tn";

    private static final String META_TEXT = """
            !!id:"https://example.test/meta-headers.tn"
            !!meta:"https://tson.io/2026/38/m/meta-kernel.tn"
            !!import:"https://tson.io/2026/38/m/meta.tn"
            {
              header_name => !identifier_type { continue_add: "-" }
              headers     => namespace & { key_type: = header_name  value_type: = text }
            }
            """;

    private static final String USER_TEXT = """
            !!id:"https://example.test/request.tn"
            !!meta:"https://tson.io/2026/38/m/meta.tn"
            !!import:"https://example.test/meta-headers.tn"
            {
              request => { headers: headers }
            }
            """;

    private static final JsonCompiledSchema COMPILED = JsonSchemaCompiler.compile(Tson.of(ProcessorConfig.defaults()
            .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(Map.of(META, META_TEXT))))).resolve(USER_TEXT));

    private static List<Diagnostic> read(String json) {
        List<Diagnostic> problems = new ArrayList<>();
        DiagnosticsReceiver receiver = problems::add;
        try (ByteSource bytes = ByteSource.of(json)) {
            JsonReadContext ctx = JsonReadContext.of(new JsonStream(bytes, ProcessorPolicy.defaults(), receiver),
                    receiver);
            ctx = COMPILED.rootDeclaration("request").map(ctx::underDeclaration).orElse(ctx);
            COMPILED.get("request").read(ctx);
        }
        return problems;
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
}
