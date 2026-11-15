package io.ltr8.tson.json;

import io.ltr8.tson.Tson;
import io.ltr8.tson.base.CanonicalIdentity;
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
 * A JSON value at a position an extern types ([TSON-SCHEMA] §5.13) is read against the foreign entry in its own
 * schema's scope, which this encoding does not yet do: the read reports the gap, never a verdict on the document.
 */
class JsonExternReadTest {

    private static final Map<String, String> DOCUMENTS = Map.of(
            "https://example.test/order-1.tn", """
                    !!id:"https://example.test/order-1.tn"
                    !!import:"https://tson.io/2026/40/m/core.tn"
                    !schema { order => { id: text } }""",
            "https://example.test/envelope.tn", """
                    !!id:"https://example.test/envelope.tn"
                    !!import:"https://tson.io/2026/40/m/core.tn"
                    !schema {
                      order_v1 => !meta.extern { schema: "https://example.test/order-1.tn"  type: order }
                      envelope => { id: text  body: order_v1 }
                    }""");

    @Test
    void aValueReadThroughAnExternIsReportedAsNotImplemented() {
        SchemaSource source = uri -> DOCUMENTS.entrySet().stream()
                .filter(document -> CanonicalIdentity.sameIdentity(uri, document.getKey()))
                .map(Map.Entry::getValue).findFirst().orElseThrow();
        Tson tson = Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(source)));
        JsonCompiledSchema compiled = JsonSchemaCompiler.compile(
                tson.resolve(DOCUMENTS.get("https://example.test/envelope.tn")));
        List<Diagnostic> problems = new ArrayList<>();
        DiagnosticsReceiver receiver = problems::add;
        try (ByteSource bytes = ByteSource.of("{\"id\": \"a\", \"body\": {\"id\": \"x\"}}")) {
            JsonReadContext context = JsonReadContext.of(
                    new JsonStream(bytes, ProcessorPolicy.defaults(), receiver), receiver);
            compiled.get("envelope").read(context);
        }
        assertEquals(List.of(Diagnostic.Code.NOT_IMPLEMENTED), problems.stream().map(Diagnostic::code).toList(),
                problems.toString());
    }
}
