package io.ltr8.tson;

import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.meta.ReferenceBody;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * A reference in resolved output ([TSON-SCHEMA] §4.2, §8.1): its body writes {@code of} alone, the names it admits
 * being the linker's judgement and never written, and reads back through the kernel's own {@code reference}
 * vocabulary.
 */
class KindResolvedFormTest {

    private static final String SCHEMA = """
            !!id:"https://example.test/kind-resolved.tn"
            !!import:"https://tson.io/2026/39/m/meta.tn"
            !schema {
              stock_id    => !uuid_type {}
              customer_id => !uuid_type {}
              any_id      => !reference { of: uuid_type }
            }""";

    private static Tson tson() {
        return Tson.of(ProcessorConfig.defaults().withDataBindContext(SchemaMetaNameBinder.defaultContext()));
    }

    @Test
    void theResolvedFormWritesOfAloneAndReadsBack() {
        Tson tson = tson();
        ReferenceBody linked = assertInstanceOf(ReferenceBody.class,
                tson.resolve(SCHEMA).schema().entries().get("any_id"));
        assertEquals(List.of("customer_id", "stock_id"), linked.memberNames());

        String written = tson.objectWriter().toTson(linked);
        assertEquals("{ chain: { parents: [] supertypes: [] subtypes: [] } of: \"uuid_type\" }", written);

        ReferenceBody read = assertInstanceOf(ReferenceBody.class,
                tson.objectReader().withSchema(TsonBundledSchemas.META_ID).readAs(written, "reference", Object.class));
        assertEquals(Optional.empty(), read.index());
        assertEquals(linked, read);
    }
}
