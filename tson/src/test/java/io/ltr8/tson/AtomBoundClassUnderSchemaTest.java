package io.ltr8.tson;

import io.ltr8.annotation.DataBridge;
import io.ltr8.annotation.Transparent;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.DiagnosticsCollector;
import io.ltr8.tson.base.ReadException;
import io.ltr8.tson.base.TsonConfig;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.TsonObjectReader;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.util.UUID;

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
 * <p>The schemaless readers get this from {@code tson-bind}, which collects a record's constructor
 * arguments through their bridges. A schema-driven read builds its own arguments from the compiled field
 * readers, so it applies the bridge itself, in the same field-wiring step that rebinds a {@code value} slot
 * and a container -- {@code RecordBindReader} over {@code ElementBridging}, the wrapper an array's and a
 * map's elements already take for the identical reason.
 *
 * <p>A value the family refuses at such a position is an ordinary located diagnostic rather than the
 * constructor's own cast: the refusal happens in the field's reader, which has a position to name.
 */
class AtomBoundClassUnderSchemaTest {

    private static final String ID = "https://example.test/invoice-1.tn";

    private static final String SCHEMA = """
            !!id:"https://example.test/invoice-1.tn"
            !!meta:"https://tson.io/2026/35/m/meta.tn"
            !!import:"https://tson.io/2026/35/m/core.tn"
            {
              money => text
              invoice => { total: money }
            }
            """;

    private static final String DOC = """
            !!schema:"https://example.test/invoice-1.tn"
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

    /** A consumer's own type whose wire form is a {@link UUID} -- a host type [TSON-DATA] §4 never produces. */
    public record Ticket(UUID id) {
    }

    public static class TicketBridge implements DataBridge<UUID, Ticket> {
        @Override
        public UUID toData(Ticket t) {
            return t.id();
        }

        @Override
        public Ticket toObject(UUID id) {
            return new Ticket(id);
        }
    }

    // ── Route 2: @Transparent, the same idea with no bridge to write ─────

    @Transparent
    public record Sku(String value) {
    }

    public record TransparentInvoice(Sku total) {
    }

    // ── Setup ────────────────────────────────────────────────────────────

    /** {@code invoice} binds to {@code bound}; every other name resolves as the meta layer's own. */
    private static DataBindContext context(Class<?> bound, boolean withBridge) {
        DataNameBinder binder = name -> "invoice".equals(name) ? bound : SchemaMetaNameBinder.INSTANCE.resolve(name);
        DataBindContext.Builder builder = DataBindContext.builder().nameBinder(binder)
                .registerAtoms(AtomContext.hostTypes());
        return (withBridge ? builder.registerAtom(Money.class, new MoneyBridge()) : builder).build();
    }

    private static Tson tson(String schema, DataBindContext context) {
        SchemaSource source = uri -> schema;
        return Tson.of(TsonConfig.defaults().withSchemaAccess(SchemaAccess.of(source)).withDataBindContext(context));
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

    /** And a collecting read of a good document reports nothing -- it used to throw straight past the receiver. */
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

    // ── The `value` escape hatch, which reaches a bridged component too ──

    /**
     * A schema reaching {@code value} imports meta-kernel.tn, which is why this is a meta-layer facility
     * rather than something a consumer adopts: meta-kernel.tn and core.tn both declare {@code void}, so a
     * schema reaching the escape hatch gives up the standard library to do it.
     */
    private static final String VALUE_SCHEMA = """
            !!id:"https://example.test/holder-1.tn"
            !!meta:"https://tson.io/2026/35/m/meta.tn"
            !!import:"https://tson.io/2026/35/m/meta-kernel.tn"
            {
              holder => { slot: value }
            }
            """;

    private static final String VALUE_DOC = """
            !!schema:"https://example.test/holder-1.tn"
            !holder { slot: "%s" }""";

    public record UuidHolder(UUID slot) {
    }

    public record MoneyHolder(Money slot) {
    }

    public record TicketHolder(Ticket slot) {
    }

    private static Tson valueTson(Class<?> bound) {
        DataNameBinder binder = name -> "holder".equals(name) ? bound : SchemaMetaNameBinder.INSTANCE.resolve(name);
        return tson(VALUE_SCHEMA, DataBindContext.builder().nameBinder(binder)
                .registerAtoms(AtomContext.hostTypes())
                .registerAtom(Money.class, new MoneyBridge())
                .registerAtom(Ticket.class, new TicketBridge()).build());
    }

    /**
     * The slot's own rebind: the component is typed {@link UUID}, {@code ValueParser.at} finds the family
     * that produces one, and the slot reads as a UUID rather than as §4's string.
     */
    @Test
    void aValueSlotLetsABuiltinHostTypePickTheAtom() {
        UUID id = UUID.fromString("f81d4fae-7dec-11d0-a765-00a0c91e6bf6");
        UuidHolder holder = valueTson(UuidHolder.class).objectReader()
                .read(VALUE_DOC.formatted(id), UuidHolder.class);
        assertEquals(id, holder.slot());
    }

    /** And a failure there is an ordinary classified read error, located at the field. */
    @Test
    void aValueSlotReportsAClassifiedErrorRatherThanACast() {
        ReadException e = assertThrows(ReadException.class, () -> valueTson(UuidHolder.class).objectReader()
                .read(VALUE_DOC.formatted("not-a-uuid"), UuidHolder.class));
        assertTrue(e.getMessage().contains("slot"), "names the field: " + e.getMessage());
    }

    /** A bridged component reaches a {@code value} slot too: the rebind asks by the class the bridge takes. */
    @Test
    void aValueSlotReachesABridgedComponentThroughTheFieldsOwnBridge() {
        MoneyHolder holder = valueTson(MoneyHolder.class).objectReader()
                .read(VALUE_DOC.formatted("12.34"), MoneyHolder.class);
        assertEquals(new Money("12.34"), holder.slot());
    }

    /**
     * The wire class is what picks the family, and only a bridge crossing something §4 does not resolve to
     * can show it: {@code Money}'s is {@code String}, which §4 produces anyway, so the slot would read the
     * same either way. {@code Ticket}'s is a {@link UUID}, so asking by the declared component class finds no
     * family, leaves §4's string, and hands the bridge a value it cannot take.
     */
    @Test
    void aValueSlotAsksByTheClassTheBridgeTakesRatherThanTheOneTheComponentDeclares() {
        UUID id = UUID.fromString("f81d4fae-7dec-11d0-a765-00a0c91e6bf6");
        TicketHolder holder = valueTson(TicketHolder.class).objectReader()
                .read(VALUE_DOC.formatted(id), TicketHolder.class);
        assertEquals(new Ticket(id), holder.slot());
    }
}
