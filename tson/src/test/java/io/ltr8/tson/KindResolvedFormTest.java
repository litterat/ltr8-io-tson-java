package io.ltr8.tson;

import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.meta.KindBody;
import io.ltr8.tson.schema.meta.Top;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * A kind in resolved output ([TSON-SCHEMA] §4.2, §8.1): its body writes {@code of} and the derived
 * {@code members} index, and reads back through meta.tn's own {@code kind_type} vocabulary. The whole {@code
 * !kind_type { ... }} spelling is what the Class 2 corpus's schema-layer vector compares.
 */
class KindResolvedFormTest {

    private static final String SCHEMA = """
            !!id:"https://example.test/kind-resolved.tn"
            !!meta:"https://tson.io/2026/39/m/meta.tn"
            {
              stock_id    => !uuid_type {}
              customer_id => !uuid_type {}
              any_id      => !kind_type { of: uuid_type }
            }""";

    private static Tson tson() {
        return Tson.of(ProcessorConfig.defaults().withDataBindContext(SchemaMetaNameBinder.defaultContext()));
    }

    @Test
    void theResolvedFormWritesTheDerivedIndexesAndReadsBack() {
        Tson tson = tson();
        Top linked = tson.resolve(SCHEMA).schema().entries().get("any_id");

        String written = tson.objectWriter().toTson(linked);
        assertEquals("{ chain: { parents: [] supertypes: [] subtypes: [] } of: \"uuid_type\" members: [ \"customer_id\" "
                + "\"stock_id\" ] }", written);

        KindBody read = assertInstanceOf(KindBody.class, tson.objectReader().withSchema(TsonBundledSchemas.META_ID)
                .readAs(written, "kind_type", Object.class));
        assertEquals(Optional.of(List.of("customer_id", "stock_id")), read.members());
    }
}
