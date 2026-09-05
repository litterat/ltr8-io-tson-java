package io.ltr8.tson;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.compiler.TsonObjectDocument;
import io.ltr8.tson.compiler.TsonObjectWriter;
import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.compiler.TsonWriteException;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TsonObjectDocument} -- what a read establishes about a document that the bound object cannot hold.
 *
 * <p>Not a symmetry exercise with {@code TsonDocument}: the class plus its bind context already fix which
 * schema governs an object, so the schema is the weakest of the three components. The other two cannot be
 * recovered from anything the caller holds -- {@code !!id} is per-document data (§2.2 makes it a property of
 * the document, so modelling it as a field would misstate the type's shape), and the root type is a name a
 * {@code DataNameBinder} cannot hand back, mapping name to class where a binding profile lets one class
 * serve several shapes.
 */
class TsonObjectDocumentRoundTripTest {

    private static final String SCHEMA_URI = "https://example.test/orders.tn";

    private static final String SCHEMA = """
            !!id:"%s"
            !!meta:"https://tson.io/2026/35/m/meta.tn"
            !!import:"https://tson.io/2026/35/m/core.tn"
            {
              order => { sku: text  quantity: int32 }
            }
            """.formatted(SCHEMA_URI);

    @Typename(name = "order")
    public record Order(String sku, int quantity) {
    }

    private static Tson tson() {
        TsonSchemaSource source = uri -> SCHEMA;
        Tson tson = Tson.builder().schemaSource(source).bindings(Map.of("order", Order.class)).build();
        tson.resolve(SCHEMA);
        return tson;
    }

    /** The whole point: read, write back, and get the same document -- not just the same object. */
    @Test
    void aDocumentSurvivesBeingReadAndWrittenBack() {
        String source = """
                !!id:"https://example.test/orders/1.tn"
                !!schema:"%s"
                !order { sku: "ABC-1"  quantity: 3 }""".formatted(SCHEMA_URI);

        TsonObjectDocument<Order> document = tson().objectReader().readDocument(source, Order.class);

        assertEquals(Optional.of("https://example.test/orders/1.tn"), document.id());
        assertEquals(Optional.of(SCHEMA_URI), document.schema());
        assertEquals(Optional.of("order"), document.rootType());
        assertEquals(new Order("ABC-1", 3), document.value());

        String written = new TsonObjectWriter().toTson(document);

        TsonObjectDocument<Order> reread = tson().objectReader().readDocument(written, Order.class);
        assertEquals(document, reread, "the document round-trips, header and root type included");
    }

    /**
     * <b>The two facts that motivate the type, isolated.</b> Neither is recoverable from {@code Order.class}
     * or from the bind context: an id belongs to this document alone, and the binder maps name to class
     * rather than back.
     */
    @Test
    void theIdAndRootTypeAreWhatTheObjectCannotCarry() {
        TsonObjectDocument<Order> document = tson().objectReader().readDocument("""
                !!id:"https://example.test/orders/1.tn"
                !!schema:"%s"
                !order { sku: "ABC-1"  quantity: 3 }""".formatted(SCHEMA_URI), Order.class);

        // The object alone -- what `read` hands back -- knows neither.
        Order plain = tson().objectReader().read("""
                !!schema:"%s"
                !order { sku: "ABC-1"  quantity: 3 }""".formatted(SCHEMA_URI), Order.class);

        assertEquals(plain, document.value());
        assertEquals(Optional.of("https://example.test/orders/1.tn"), document.id());
        assertEquals(Optional.of("order"), document.rootType());
    }

    /** {@code read} is untouched: the wrapper is additive. */
    @Test
    void readStillHandsBackTheObjectAlone() {
        String source = """
                !!schema:"%s"
                !order { sku: "ABC-1"  quantity: 3 }""".formatted(SCHEMA_URI);

        assertEquals(tson().objectReader().read(source, Order.class),
                tson().objectReader().readDocument(source, Order.class).value());
    }

    /** A schemaless read resolves no type, so it says so rather than guessing from a wire type-ref. */
    @Test
    void aSchemalessReadLeavesTheRootTypeEmpty() {
        TsonObjectDocument<Order> document =
                tson().objectReader().readDocument("{ sku: \"ABC-1\"  quantity: 3 }", Order.class);

        assertEquals(Optional.empty(), document.schema());
        assertEquals(Optional.empty(), document.rootType());
        assertEquals(new Order("ABC-1", 3), document.value());
    }

    /** The document's own facts beat the writer's, and only where it has them. */
    @Test
    void aDocumentsOwnFactsWinOverTheWritersAndOnlyWhereItHasThem() {
        TsonObjectDocument<Order> governed = tson().objectReader().readDocument("""
                !!schema:"%s"
                !order { sku: "ABC-1"  quantity: 3 }""".formatted(SCHEMA_URI), Order.class);

        String written = new TsonObjectWriter()
                .describing("https://example.test/ignored.tn", "ignored")
                .identifiedBy("https://example.test/orders/9.tn")
                .toTson(governed);

        assertTrue(written.contains(SCHEMA_URI), written);
        assertTrue(!written.contains("ignored"), () -> "the document's schema and type, not the writer's: " + written);
        assertTrue(written.contains("orders/9.tn"),
                () -> "and the writer's id, which the document does not state: " + written);
    }

    /**
     * A hand-assembled document naming a schema and no type is refused, because the pair is what makes a
     * document self-describing -- the directive alone leaves a reader with a schema and no way to pick a
     * type from it. A read never produces this shape; only a caller building one can.
     */
    @Test
    void aSchemaWithNoRootTypeIsRefusedRatherThanWrittenHalfDescribed() {
        TsonObjectDocument<Order> handAssembled = new TsonObjectDocument<>(
                Optional.empty(), Optional.of(SCHEMA_URI), Optional.empty(), new Order("ABC-1", 3));

        TsonWriteException thrown =
                assertThrows(TsonWriteException.class, () -> new TsonObjectWriter().toTson(handAssembled));
        assertTrue(thrown.getMessage().contains("no root type"), thrown.getMessage());
    }

    /** {@code withValue} is generic, so a caller may map the payload without restating what governed it. */
    @Test
    void withValueKeepsTheHeaderAcrossAProjection() {
        TsonObjectDocument<Order> document = tson().objectReader().readDocument("""
                !!id:"https://example.test/orders/1.tn"
                !!schema:"%s"
                !order { sku: "ABC-1"  quantity: 3 }""".formatted(SCHEMA_URI), Order.class);

        TsonObjectDocument<String> projected = document.withValue(document.value().sku());

        assertEquals("ABC-1", projected.value());
        assertEquals(document.id(), projected.id());
        assertEquals(document.schema(), projected.schema());
        assertEquals(document.rootType(), projected.rootType());
    }

    /** A document that will not parse yields nothing, exactly as {@code read} does. */
    @Test
    void anUnparseableDocumentYieldsNothing() {
        assertNull(tson().objectReader()
                .withDiagnostics(io.ltr8.tson.compiler.TsonDiagnosticsReceiver.collecting())
                .readDocument("{ a: 1  b: ] }", Order.class));
    }
}
