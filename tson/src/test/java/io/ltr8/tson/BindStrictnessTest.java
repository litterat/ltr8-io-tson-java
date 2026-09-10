package io.ltr8.tson;
import io.ltr8.tson.base.*;
import io.ltr8.tson.base.ProcessorConfig;

import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.annotation.DataBridge;
import io.ltr8.annotation.Unbound;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.base.bind.AtomContext;
import java.util.List;
import java.util.Optional;
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.tson.compiler.TsonObjectWriter;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The schema and the class bound to it must agree about a type's fields. The motivating case is versioned
 * evolution: a codec whose binder maps {@code order} to a v1 class, handed a v2 document. It returned the v1
 * record with the new field gone -- no exception, and nothing reported even under a collecting receiver,
 * while a tree read of the same document kept the field. The document was read correctly against its own
 * schema; it was the bind that discarded it.
 *
 * <p><b>Why strict is the default.</b> Both halves are fixed before any document exists, so the mismatch is
 * knowable when the schema is compiled in bind mode -- startup, for anything compiling its schemas once. A
 * strict reader that is wrong says so there, in one message naming both sides. A lenient one that is wrong
 * drops a value from every document and shows up much later as a field mysteriously holding its default.
 */
class BindStrictnessTest {

    private static final String ID = "https://example.test/order-2.tn";

    private static final String SCHEMA = """
            !!id:"https://example.test/order-2.tn"
            !!meta:"https://tson.io/2026/35/m/meta.tn"
            !!import:"https://tson.io/2026/35/m/core.tn"
            {
              order => { sku: text  quantity: int32  currency: text }
            }
            """;

    private static final String OPTIONAL_SCHEMA = SCHEMA.replace("currency: text", "currency: text?");

    private static final String DOC = """
            !!schema:"https://example.test/order-2.tn"
            !order { sku: "A"  quantity: 1  currency: "AUD" }""";

    /** The v1 class: no component for the field v2 added. */
    public record OrderV1(String sku, int quantity) {
    }

    /** A class with a component no schema field fills -- it would reach the constructor as null. */
    public record OrderV3(String sku, int quantity, String currency, String region) {
    }

    /** The same, with the extra component declared as the class's own. */
    public record OrderTraced(String sku, int quantity, String currency, @Unbound Optional<String> trace) {
    }

    private static Tson tson(String schema, Class<?> bound) {
        SchemaSource source = uri -> schema;
        DataNameBinder binder = name -> "order".equals(name) ? bound : SchemaMetaNameBinder.INSTANCE.resolve(name);
        return Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(source))
                .withDataBindContext(DataBindContext.builder().nameBinder(binder)
                        .registerAtoms(AtomContext.hostTypes()).build()));
    }

    private static Object read(Tson tson, Class<?> bound, DiagnosticsReceiver receiver) {
        return tson.objectReader().withDiagnostics(receiver).read(DOC, bound);
    }

    /**
     * A field every document carries, with no component to carry it, fails where the schema meets the class
     * -- before any document is involved. Nothing about this needs a document to discover.
     */
    @Test
    void aRequiredFieldTheClassCannotHoldFailsAtCompile() {
        Tson tson = tson(SCHEMA, OrderV1.class);

        BindMismatchException thrown = assertThrows(BindMismatchException.class,
                () -> tson.bindRegistry().get(ID));

        assertTrue(thrown.getMessage().contains("no component for field 'currency'"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("OrderV1"), thrown.getMessage());
    }

    /** And the converse: a component no field fills would be constructed null on every document. */
    @Test
    void aComponentNoFieldFillsFailsAtCompileToo() {
        Tson tson = tson(SCHEMA, OrderV3.class);

        BindMismatchException thrown = assertThrows(BindMismatchException.class,
                () -> tson.bindRegistry().get(ID));

        assertTrue(thrown.getMessage().contains("component 'region'"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("@Unbound"), "the message names the way to say it is deliberate");
    }

    /**
     * {@code @Unbound} is that way, and it is <b>per component</b>: the class names which one it owns, where
     * a blanket "accept fewer fields" would say only how many. An {@code Optional} component arrives empty
     * rather than null, the bind engine wrapping it as it does any other.
     */
    @Test
    void anUnboundComponentIsTheClassesOwnBusiness() {
        Object value = read(tson(SCHEMA, OrderTraced.class), OrderTraced.class,
                DiagnosticsReceiver.throwing());

        assertEquals(new OrderTraced("A", 1, "AUD", Optional.empty()), value);
    }

    /**
     * <b>An {@code @Unbound} component is absent from the descriptor's field list, and present in its
     * constructor arity.</b> That is what the marker means: the component belongs to the class and not to
     * the wire, so nothing may fill it from a document and nothing may write it. The values array a reader
     * fills stays dense -- the constructor supplies the class's own components itself -- so
     * {@code fields().length} remains the only notion of size any caller needs.
     *
     * <p>Without the split, an unbound component reaches the writer indistinguishable from a bound one and
     * is emitted -- which for {@code schema.meta.TypeDefinition} meant this library could not produce
     * resolved output that validates against its own meta-kernel ([TSON-SCHEMA] §1.3, §7.2's closed record).
     */
    @Test
    void anUnboundComponentIsNotAFieldAndIsNotWritten() throws Exception {
        DataBindContext context = DataBindContext.builder()
                .nameBinder(name -> OrderTraced.class).build();
        DataClassRecord descriptor = (DataClassRecord) context.getDescriptor(OrderTraced.class);

        List<String> published = Arrays.stream(descriptor.fields()).map(DataClassField::name).toList();
        assertEquals(List.of("sku", "quantity", "currency"), published, "the wire-facing fields");

        String written = new TsonObjectWriter(context).toTson(new OrderTraced("A", 1, "AUD", Optional.of("t")));
        assertFalse(written.contains("trace"), written);

        // And it still constructs: the class's own component arrives as the engine's absent value, not as a
        // hole in the array, which is what keeps the read side of `@Unbound` unchanged.
        assertEquals(new OrderTraced("A", 1, "AUD", Optional.empty()),
                read(tson(SCHEMA, OrderTraced.class), OrderTraced.class,
                        DiagnosticsReceiver.throwing()));
    }

    /**
     * An <b>optional</b> field is not exempt, and that is the whole of the rule: the class must be able to
     * hold what the schema declares, FIXED excepted. Leaving optional fields to the read that writes one
     * would report the mismatch that is hardest to find -- the field that works in development and fails the
     * first time a caller sends it -- at exactly the moment it has already gone wrong.
     */
    @Test
    void anOptionalFieldTheClassCannotHoldFailsAtCompileToo() {
        Tson tson = tson(OPTIONAL_SCHEMA, OrderV1.class);

        BindMismatchException thrown = assertThrows(BindMismatchException.class,
                () -> tson.bindRegistry().get(ID));

        assertTrue(thrown.getMessage().contains("no component for field 'currency'"), thrown.getMessage());
    }

    /**
     * The same mismatch reaching a <b>collecting</b> caller keeps saying what it is. A reader that compiles
     * its schema on demand meets the mismatch inside a read, where there is no exception to classify on --
     * only a {@link Diagnostic} and its code. {@code SCHEMA_ERROR} would be a false verdict twice over: the
     * document is valid, the schema is valid, and a consumer routing on the code (an HTTP status, an exit
     * code) would blame the sender for the reader's own wiring.
     */
    @Test
    void aMismatchMetDuringAReadIsCodedAsOneRatherThanAsABadSchema() {
        Tson tson = tson(SCHEMA, OrderV1.class);
        DiagnosticsCollector problems = DiagnosticsReceiver.collecting();

        assertNull(read(tson, OrderV1.class, problems));

        assertEquals(1, problems.diagnostics().size(), problems.diagnostics().toString());
        Diagnostic problem = problems.diagnostics().getFirst();
        assertEquals(Diagnostic.Code.BIND_MISMATCH, problem.code());
        assertTrue(problem.message().contains("no component for field 'currency'"), problem.message());
    }

    // ── An atom field against a component that binds structurally ────────

    private static final String MONEY_SCHEMA = """
            !!id:"https://example.test/order-2.tn"
            !!meta:"https://tson.io/2026/35/m/meta.tn"
            !!import:"https://tson.io/2026/35/m/core.tn"
            {
              money => text
              order => { sku: text  quantity: int32  currency: money }
            }
            """;

    /** A consumer's own type that nothing registers as an atom, so it binds as the record it is. */
    public record Money(String amount) {
    }

    public record OrderPriced(String sku, int quantity, Money currency) {
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

    private static Tson moneyTson(boolean registered) {
        SchemaSource source = uri -> MONEY_SCHEMA;
        DataNameBinder binder = name -> "order".equals(name) ? OrderPriced.class
                : SchemaMetaNameBinder.INSTANCE.resolve(name);
        DataBindContext.Builder builder = DataBindContext.builder().nameBinder(binder)
                .registerAtoms(AtomContext.hostTypes());
        return Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(source))
                .withDataBindContext((registered ? builder.registerAtom(Money.class, new MoneyBridge())
                        : builder).build()));
    }

    /**
     * No decoded value fills a record, so this is knowable without a document -- and it is the shape a
     * consumer's own host type takes when nothing has registered it. It used to compile clean and throw a
     * bare {@code ClassCastException} out of the constructor on the first read.
     */
    @Test
    void anAtomFieldAgainstAStructuralComponentFailsAtCompile() {
        BindMismatchException thrown = assertThrows(BindMismatchException.class,
                () -> moneyTson(false).bindRegistry().get(ID));

        assertTrue(thrown.getMessage().contains("field 'currency' is an atom"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains(Money.class.getName()), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("registerAtom"), "the message names a way to say it");
    }

    /** Registering it is exactly what the message asks for, and it is then an ordinary read. */
    @Test
    void registeringTheTypeAsAnAtomSettlesIt() {
        Tson tson = moneyTson(true);
        assertNotNull(tson.bindRegistry().get(ID));
        OrderPriced order = tson.objectReader().read("""
                !!schema:"https://example.test/order-2.tn"
                !order { sku: "A"  quantity: 1  currency: "12.34" }""", OrderPriced.class);
        assertEquals(new Money("12.34"), order.currency());
    }

    /** Tree mode is unaffected: it binds no class, so it has nothing to disagree with. */
    @Test
    void treeModeKeepsEveryFieldWhateverTheBindingSays() {
        assertEquals("AUD", tson(SCHEMA, OrderV1.class).treeReader().read(DOC)
                .get("currency").asString().orElseThrow());
    }
}
