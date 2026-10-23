package io.ltr8.tson;

import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.suite.SuiteBindings;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * An entry built with a namespace type in resolved output ([TSON-SCHEMA] §8.1): written as written, the constructor's
 * tag on the map, and read back under the meta layer that declares the constructor to the same {@link Namespace} --
 * the read a Class 2 schema-layer vector's expected side takes ({@link ResolvedForm#readResolved}).
 */
class NamespaceEntryResolvedFormTest {

    private static final String META = "https://example.test/meta-api.tn";

    private static final String META_TEXT = """
            !!id:"https://example.test/meta-api.tn"
            !!meta:"https://tson.io/2026/38/m/meta-kernel.tn"
            !!import:"https://tson.io/2026/38/m/meta.tn"
            {
              status_name => identifier
              method      => { request?: kind<record>  response?: type_kind  errors?: [kind<record>]
                               replies?: {status_name => kind<record>} }
              api         => namespace & { key_type: = identifier  value_type: = method }
            }
            """;

    private static final String SCHEMA = """
            !!id:"https://example.test/api.tn"
            !!meta:"https://example.test/meta-api.tn"
            !!import:"https://tson.io/2026/38/m/core.tn"
            {
              order      => { sku: text }
              order_list => [order]
              myapi      => !api {
                get_orders  => { response: order_list }
                place_order => !method { request: order  response: order }
              }
            }""";

    private static Tson tson() {
        return Tson.of(ProcessorConfig.defaults()
                .withDataBindContext(SuiteBindings.context())
                .withMetaNameBinder(SuiteBindings.names())
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(Map.of(META, META_TEXT)))));
    }

    @Test
    void aNamespaceEntryWritesAsWrittenAndReadsBackAsANamespace() {
        Tson tson = tson();
        Top myapi = tson.resolve(SCHEMA).schema().entries().get("myapi");

        String written = tson.objectWriter().toTson(myapi);
        assertEquals("!api { get_orders => { response: order_list } place_order => { request: order response: order } }",
                written.replaceAll("\\s+", " "));

        Map<String, Top> read = ResolvedForm.readResolved(tson, "!schema { myapi => " + written + " }", META);
        Namespace<?> back = assertInstanceOf(Namespace.class, read.get("myapi"));
        assertEquals(myapi, back);
        assertEquals(List.of("get_orders", "place_order"), List.copyOf(back.entries().keySet()));
        assertEquals(ResolvedForm.rendered(myapi), ResolvedForm.rendered(back));
    }
}
