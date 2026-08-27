package io.ltr8.tson;

import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.compiler.TsonDiagnosticsReceiver;
import io.ltr8.tson.compiler.TsonTreeReader;
import io.ltr8.tson.compiler.TsonTreeWriter;
import io.ltr8.tson.tree.TsonDocument;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TsonDocument} -- the tree model's own document, and the round trip it closes.
 *
 * <p>A schema-driven read already recorded each node's type, so a writer could put the root's
 * {@code !typeName} back; what it discarded was the document's own {@code !!schema} and {@code !!id}. So a
 * tree could be reproduced only by a caller who still held the URI -- fine when the reader and the writer
 * are the same code, and impossible the moment a tree is handed on, which is the server case.
 */
class TsonDocumentRoundTripTest {

    private static final String SCHEMA_URI = "https://example.test/orders.tn";

    private static final String SCHEMA = """
            !!id:"%s"
            !!meta:"https://tson.io/2026/33/m/meta.tn"
            !!import:"https://tson.io/2026/33/m/core.tn"
            {
              order => { sku: text  quantity: int32 }
            }
            """.formatted(SCHEMA_URI);

    private static Tson tson() {
        TsonSchemaSource source = uri -> SCHEMA;
        Tson tson = Tson.builder().schemaSource(source).build();
        tson.resolve(SCHEMA);
        return tson;
    }

    /** The whole point: read a document, write it back, get the document back -- header included. */
    @Test
    void aDocumentSurvivesBeingReadAndWrittenBack() {
        String source = """
                !!id:"https://example.test/orders/1.tn"
                !!schema:"%s"
                !order { sku: "ABC-1"  quantity: 3 }""".formatted(SCHEMA_URI);

        TsonDocument document = tson().treeReader().readDocument(source);

        assertEquals(Optional.of("https://example.test/orders/1.tn"), document.id());
        assertEquals(Optional.of(SCHEMA_URI), document.schema());
        assertEquals("ABC-1", document.root().get("sku").asString().orElseThrow());

        String written = new TsonTreeWriter().toTson(document);
        assertTrue(written.contains("!!id:\"https://example.test/orders/1.tn\""), written);
        assertTrue(written.contains("!!schema:\"" + SCHEMA_URI + "\""), written);

        // And the reproduction reads back as the same document, which is the assertion that matters --
        // the text may differ in whitespace, the document may not.
        TsonDocument reread = tson().treeReader().readDocument(written);
        assertEquals(document.id(), reread.id());
        assertEquals(document.schema(), reread.schema());
        assertEquals(document.root(), reread.root());
    }

    /** {@link Tson#treeReader()}'s {@code read} is untouched: the wrapper is additive, not a replacement. */
    @Test
    void readStillHandsBackTheValueAlone() {
        String source = """
                !!schema:"%s"
                !order { sku: "ABC-1"  quantity: 3 }""".formatted(SCHEMA_URI);

        TsonValue value = tson().treeReader().read(source);
        TsonDocument document = tson().treeReader().readDocument(source);

        assertEquals(value, document.root(), "the same tree either way");
    }

    /** A bare value is an ordinary Class 1 document, and comes back with an empty header rather than a pretend one. */
    @Test
    void aDocumentWithNoDirectivesHasAnEmptyHeader() {
        TsonDocument document = new TsonTreeReader().readDocument("{ sku: \"ABC-1\" }");

        assertEquals(Optional.empty(), document.id());
        assertEquals(Optional.empty(), document.schema());
        assertEquals("ABC-1", document.root().get("sku").asString().orElseThrow());
    }

    /** Only one directive is just as ordinary -- neither implies the other. */
    @Test
    void eitherDirectiveMayStandAlone() {
        TsonDocument identified = new TsonTreeReader().readDocument("""
                !!id:"https://example.test/orders/1.tn"
                { sku: "ABC-1" }""");
        assertEquals(Optional.of("https://example.test/orders/1.tn"), identified.id());
        assertEquals(Optional.empty(), identified.schema());

        TsonDocument governed = tson().treeReader().readDocument("""
                !!schema:"%s"
                !order { sku: "ABC-1"  quantity: 3 }""".formatted(SCHEMA_URI));
        assertEquals(Optional.empty(), governed.id());
        assertEquals(Optional.of(SCHEMA_URI), governed.schema());
    }

    /**
     * The document's own header wins over the writer's, component by component and only where it has one --
     * so reproducing a document reproduces it, while a writer configured for what the document does not
     * state still contributes it.
     */
    @Test
    void aDocumentsOwnHeaderWinsOverTheWritersAndOnlyWhereItHasOne() {
        TsonDocument governed = tson().treeReader().readDocument("""
                !!schema:"%s"
                !order { sku: "ABC-1"  quantity: 3 }""".formatted(SCHEMA_URI));

        String written = new TsonTreeWriter()
                .describing("https://example.test/ignored.tn")
                .identifiedBy("https://example.test/orders/9.tn")
                .toTson(governed);

        assertTrue(written.contains(SCHEMA_URI), () -> "the document's schema, not the writer's: " + written);
        assertTrue(!written.contains("ignored.tn"), written);
        assertTrue(written.contains("orders/9.tn"),
                () -> "and the writer's id, which the document does not state: " + written);
    }

    /** A document that will not parse yields nothing, exactly as {@code read} does -- one shape, not two. */
    @Test
    void anUnparseableDocumentYieldsNothingJustAsReadDoes() {
        Tson tson = tson();
        assertNull(tson.treeReader().withDiagnostics(TsonDiagnosticsReceiver.collecting())
                .readDocument("{ a: 1  b: ] }"));
    }

    /** {@code withRoot} carries the header across a genuinely different value -- the tree has no builders yet. */
    @Test
    void withRootKeepsTheHeader() {
        TsonDocument document = tson().treeReader().readDocument("""
                !!id:"https://example.test/orders/1.tn"
                !!schema:"%s"
                !order { sku: "ABC-1"  quantity: 3 }""".formatted(SCHEMA_URI));
        TsonValue other = new TsonTreeReader().read("{ sku: \"OTHER\" }");

        TsonDocument replaced = document.withRoot(other);

        assertEquals(document.id(), replaced.id());
        assertEquals(document.schema(), replaced.schema());
        assertEquals(other, replaced.root());
        assertEquals(document.root(), document.withRoot(other).withRoot(document.root()).root());
    }
}
