package io.ltr8.tson;

import io.ltr8.annotation.DataBridge;
import io.ltr8.annotation.Transparent;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.DiagnosticsCollector;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.TsonObjectReader;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bound class standing for an atom binds the same under a schema as it does without one.
 *
 * <p>Both routes a consumer has for giving an atom position their own host type are binding-side:
 * {@code DataBindContext.Builder.registerAtom(Class, DataBridge)}, and {@code @Transparent} on a
 * single-component record. Each produces a {@code DataClassAtom} whose {@code dataClass()} is the wire type
 * and whose bridge crosses to the consumer's own class, so what a reader owes at such a position is the
 * wire value put through that bridge.
 *
 * <p>The schemaless readers get this from {@code ltr8-bind}, which collects a record's constructor
 * arguments through their bridges. A schema-driven read builds its own arguments from the compiled field
 * readers, so it applies the bridge itself, in the same field-wiring step that rebinds a container -- {@code
 * RecordBindReader} over {@code ElementBridging}, the wrapper an array's and a
 * map's elements already take for the identical reason.
 *
 * <p>A value the family refuses at such a position is an ordinary located diagnostic rather than the
 * constructor's own cast: the refusal happens in the field's reader, which has a position to name.
 */
class AtomBoundClassUnderSchemaTest {

    private static final String ID = "https://example.test/invoice-1.tn";

    private static final String SCHEMA = """
            !!id:"https://example.test/invoice-1.tn"
            !!import:"https://tson.io/2026/39/m/meta.tn"
            !!import:"https://tson.io/2026/39/m/core.tn"
            !schema {
              money => text
              invoice => { total: money }
            }
            """;

    private static final String DOC = """
            !!import:"https://example.test/invoice-1.tn"
            !invoice { total: "12.34" }""";

    private static final String BARE = "{ total: \"12.34\" }";

    // ── Route 1: a registered bridge ─────────────────────────────────────

    /** A consumer's own money type, whose wire form is {@code text}'s host value. */
    public record Money(String amount) {
    }

    public static class MoneyBridge implements DataBridge<String, Money> {
        @Override
        public String toData(Money m) {
            return m.amount();
        }

        @Override
        public Money toObject(String s) {
            return new Money(s);
        }
    }

    public record BridgedInvoice(Money total) {
    }

    // ── Route 2: @Transparent, the same idea with no bridge to write ─────

    @Transparent
    public record Sku(String value) {
    }

    public record TransparentInvoice(Sku total) {
    }

    // ── Setup ────────────────────────────────────────────────────────────

    /** {@code invoice} binds to {@code bound}; every other name resolves as the schema model's own. */
    private static DataBindContext context(Class<?> bound, boolean withBridge) {
        DataNameBinder binder = name -> "invoice".equals(name) ? bound : SchemaModelNameBinder.INSTANCE.resolve(name);
        DataBindContext.Builder builder = DataBindContext.builder().nameBinder(binder)
                .registerAtoms(AtomContext.hostTypes());
        return (withBridge ? builder.registerAtom(Money.class, new MoneyBridge()) : builder).build();
    }

    private static Tson tson(String schema, DataBindContext context) {
        SchemaSource source = uri -> schema;
        return Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(source)).withDataBindContext(context));
    }

    // ── Schemaless: the answer a schema-driven read has to match ─────────

    @Test
    void schemalessABridgedAtomBinds() {
        BridgedInvoice invoice = new TsonObjectReader(context(BridgedInvoice.class, true))
                .read(BARE, BridgedInvoice.class);
        assertEquals(new Money("12.34"), invoice.total());
    }

    @Test
    void schemalessATransparentWrapperBinds() {
        TransparentInvoice invoice = new TsonObjectReader(context(TransparentInvoice.class, false))
                .read(BARE, TransparentInvoice.class);
        assertEquals(new Sku("12.34"), invoice.total());
    }

    // ── Under a schema: the same answer ──────────────────────────────────

    @Test
    void aBridgedAtomBindsUnderASchema() {
        BridgedInvoice invoice = tson(SCHEMA, context(BridgedInvoice.class, true))
                .objectReader().read(DOC, BridgedInvoice.class);
        assertEquals(new Money("12.34"), invoice.total());
    }

    /** {@code @Transparent} is the other spelling of the same thing and reads the same way. */
    @Test
    void aTransparentWrapperBindsUnderASchema() {
        TransparentInvoice invoice = tson(SCHEMA, context(TransparentInvoice.class, false))
                .objectReader().read(DOC, TransparentInvoice.class);
        assertEquals(new Sku("12.34"), invoice.total());
    }

    /** And a collecting read of a good document reports nothing, and never throws past the receiver. */
    @Test
    void aCollectingReadOfAGoodDocumentReportsNothing() {
        DiagnosticsCollector collector = new DiagnosticsCollector();
        BridgedInvoice invoice = tson(SCHEMA, context(BridgedInvoice.class, true))
                .objectReader().withDiagnostics(collector).read(DOC, BridgedInvoice.class);
        assertEquals(new Money("12.34"), invoice.total());
        assertTrue(collector.isEmpty(), "nothing to report: " + collector.diagnostics());
    }

    /** The schema and the class agree, so the bind-mode compile has nothing to refuse. */
    @Test
    void theSchemaCompilesInBindMode() {
        assertNotNull(tson(SCHEMA, context(BridgedInvoice.class, true)).bindRegistry().get(ID));
    }

    /** The document was always valid; only the bind step was losing the bridge. */
    @Test
    void theDocumentIsValidUnderTheSchema() {
        Tson tson = tson(SCHEMA, context(BridgedInvoice.class, true));
        TsonValue value = tson.treeReader().read(DOC);
        assertEquals("12.34", value.get("total").asString().orElseThrow());
        assertTrue(tson.validate(DOC).isEmpty());
    }
}
