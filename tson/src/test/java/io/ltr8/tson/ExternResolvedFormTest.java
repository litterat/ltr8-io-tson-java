package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.Extern;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.TypeRef;

import org.junit.jupiter.api.Test;

import static io.ltr8.tson.ExternWorld.ORDER_1;
import static io.ltr8.tson.ExternWorld.extern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An extern in resolved output ([TSON-SCHEMA] §8.1) is the entry as written, and a use of it the local name; and a
 * value bound through one is a gap of this library, never a verdict ([TSON-SCHEMA] §7.9).
 */
class ExternResolvedFormTest {

    private final ExternWorld world = new ExternWorld();

    private TsonLinkedSchema envelope(Tson tson) {
        tson.resolve(ExternWorld.text("https://example.test/envelope.tn",
                extern("order_v1", ORDER_1, "order") + "envelope => { id: text  body: order_v1 }"));
        return tson.schemaRegistry().get("https://example.test/envelope.tn").orElseThrow();
    }

    @Test
    void theEntryIsWrittenAsWrittenAndReadsBack() {
        Tson tson = world.tson();
        Extern resolved = assertInstanceOf(Extern.class, envelope(tson).schema().entries().get("order_v1"));
        assertEquals(new Extern(ORDER_1, "order"), resolved);

        String written = tson.objectWriter().toTson(resolved);
        assertEquals(resolved, tson.objectReader().withSchema(TsonBundledSchemas.CORE_ID)
                .readAs(written, "meta.extern", Object.class), written);
    }

    @Test
    void aUseIsWrittenByTheLocalName() {
        RecordBody envelope = assertInstanceOf(RecordBody.class,
                envelope(world.tson()).schema().entries().get("envelope"));
        assertEquals("order_v1", assertInstanceOf(TypeRef.class, envelope.fields().get("body").type()).name());
    }

    public record Card(String number) {
    }

    public record Order(String id, Card payment) {
    }

    public record Envelope(String id, Order body) {
    }

    @Test
    void aBindReadThroughAnExternFailsAsTheGapItIs() {
        Tson tson = world.tson(java.util.Map.of("envelope", Envelope.class));
        envelope(tson);
        io.ltr8.tson.base.ReadException gap = assertThrows(io.ltr8.tson.base.ReadException.class,
                () -> tson.objectReader().read("!!import:\"https://example.test/envelope.tn\"\n"
                        + "!envelope { id: a  body: { id: x  payment: { number: n } } }", Envelope.class));
        assertEquals(Diagnostic.Code.NOT_IMPLEMENTED, gap.diagnostic().code(), gap.getMessage());
        assertTrue(gap.diagnostic().message().startsWith("'order_v1' is an extern of 'order' in '" + ORDER_1
                + "', and binding a value read through an extern is not implemented yet"), gap.getMessage());
    }
}
