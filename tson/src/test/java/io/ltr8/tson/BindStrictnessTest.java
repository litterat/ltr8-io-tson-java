package io.ltr8.tson;

import io.ltr8.annotation.Unbound;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonBindMismatchException;
import io.ltr8.tson.compiler.TsonDiagnosticsCollector;
import io.ltr8.tson.compiler.TsonDiagnosticsReceiver;
import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.config.TsonAtomContext;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private static Tson tson(String schema, Class<?> bound, boolean lenient) {
        TsonSchemaSource source = uri -> schema;
        DataNameBinder binder = name -> "order".equals(name) ? bound : SchemaMetaNameBinder.INSTANCE.resolve(name);
        TsonConfig config = Tson.builder().schemaSource(source).dataBindContext(
                TsonAtomContext.registerDefaults(DataBindContext.builder().nameBinder(binder).build()));
        return (lenient ? config.lenientBinding() : config).build();
    }

    private static Object read(Tson tson, Class<?> bound, TsonDiagnosticsReceiver receiver) {
        return tson.objectReader().withDiagnostics(receiver).read(DOC, bound);
    }

    /**
     * A field every document carries, with no component to carry it, fails where the schema meets the class
     * -- before any document is involved. Nothing about this needs a document to discover.
     */
    @Test
    void aRequiredFieldTheClassCannotHoldFailsAtCompile() {
        Tson tson = tson(SCHEMA, OrderV1.class, false);

        TsonBindMismatchException thrown = assertThrows(TsonBindMismatchException.class,
                () -> tson.bindRegistry().get(ID));

        assertTrue(thrown.getMessage().contains("no component for field 'currency'"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("OrderV1"), thrown.getMessage());
    }

    /** And the converse: a component no field fills would be constructed null on every document. */
    @Test
    void aComponentNoFieldFillsFailsAtCompileToo() {
        Tson tson = tson(SCHEMA, OrderV3.class, false);

        TsonBindMismatchException thrown = assertThrows(TsonBindMismatchException.class,
                () -> tson.bindRegistry().get(ID));

        assertTrue(thrown.getMessage().contains("component 'region'"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("@Unbound"), "the message names the way to say it is deliberate");
    }

    /**
     * {@code @Unbound} is that way, and it is per component rather than per read -- the narrow answer where
     * {@link TsonConfig#lenientBinding} is the broad one. An {@code Optional} component arrives empty rather
     * than null, the bind engine wrapping it as it does any other.
     */
    @Test
    void anUnboundComponentIsTheClassesOwnBusiness() {
        Object value = read(tson(SCHEMA, OrderTraced.class, false), OrderTraced.class,
                TsonDiagnosticsReceiver.throwing());

        assertEquals(new OrderTraced("A", 1, "AUD", Optional.empty()), value);
    }

    /**
     * An <b>optional</b> field is not exempt, and that is the whole of the rule: the class must be able to
     * hold what the schema declares, FIXED excepted. Leaving optional fields to the read that writes one
     * would report the mismatch that is hardest to find -- the field that works in development and fails the
     * first time a caller sends it -- at exactly the moment it has already gone wrong.
     */
    @Test
    void anOptionalFieldTheClassCannotHoldFailsAtCompileToo() {
        Tson tson = tson(OPTIONAL_SCHEMA, OrderV1.class, false);

        TsonBindMismatchException thrown = assertThrows(TsonBindMismatchException.class,
                () -> tson.bindRegistry().get(ID));

        assertTrue(thrown.getMessage().contains("no component for field 'currency'"), thrown.getMessage());
    }

    /**
     * Leniency is the opt-out, and it is silent -- not a shortcut but the only coherent reading. Reporting
     * abandons the construction ({@code ConstructionGuard}: bind mode never builds out of a document it has
     * reported on), so a lenient reader that reported would hand back {@code null} for exactly the documents
     * it exists to accept; and a diagnostic the guard is told to ignore is a severity axis under another
     * name. It is the one path on which a field is dropped at all, now that every mismatch is settled before
     * a document exists.
     */
    @Test
    void lenientBindingDropsTheFieldSilently() {
        Tson tson = tson(SCHEMA, OrderV1.class, true);
        TsonDiagnosticsCollector problems = TsonDiagnosticsReceiver.collecting();

        assertEquals(new OrderV1("A", 1), read(tson, OrderV1.class, problems));
        assertEquals(List.of(), problems.diagnostics());
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
        Tson tson = tson(SCHEMA, OrderV1.class, false);
        TsonDiagnosticsCollector problems = TsonDiagnosticsReceiver.collecting();

        assertNull(read(tson, OrderV1.class, problems));

        assertEquals(1, problems.diagnostics().size(), problems.diagnostics().toString());
        Diagnostic problem = problems.diagnostics().getFirst();
        assertEquals(Diagnostic.Code.BIND_MISMATCH, problem.code());
        assertTrue(problem.message().contains("no component for field 'currency'"), problem.message());
    }

    /** Tree mode is unaffected: it binds no class, so it has nothing to disagree with. */
    @Test
    void treeModeKeepsEveryFieldWhateverTheBindingSays() {
        assertEquals("AUD", tson(SCHEMA, OrderV1.class, false).treeReader().read(DOC)
                .get("currency").asString().orElseThrow());
    }
}
