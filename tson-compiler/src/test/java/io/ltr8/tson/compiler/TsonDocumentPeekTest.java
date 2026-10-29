package io.ltr8.tson.compiler;

import io.ltr8.tson.tree.TsonAtom;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TsonDocumentPeek}: §7.1's classification from a document's opening bytes -- the header, then the root's
 * tag ([TSON-DATA] §2.2).
 *
 * <p>The governing rule throughout is that a peek may answer "nothing here" but must never answer with a
 * schema the document does not name -- so the adversarial cases (a directive spelled inside the value, a
 * document that stops mid-value) matter as much as the well-formed ones.
 */
class TsonDocumentPeekTest {

    /** Classification only: what the document declares, with the read it could have continued discarded. */
    private static TsonDocumentHeader peek(String source) {
        return TsonDocumentPeek.of(source).header();
    }

    private static TsonDocumentHeader peek(InputStream source) {
        return TsonDocumentPeek.of(source).header();
    }

    private static InputStream stream(String source) {
        return new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8));
    }

    /** A stream that cannot be rewound and refuses to be read twice -- an HTTP request body, in effect. */
    private static InputStream oneShot(String source) {
        return new FilterInputStream(stream(source)) {
            @Override
            public synchronized void mark(int readLimit) {
                // no-op: mark/reset is exactly what a one-shot stream does not offer
            }

            @Override
            public synchronized void reset() throws IOException {
                throw new IOException("mark/reset not supported");
            }

            @Override
            public boolean markSupported() {
                return false;
            }
        };
    }

    @Test
    void readsTheSchemaADataDocumentImports() {
        TsonDocumentPeek peek = TsonDocumentPeek.of("!!import:\"https://example.com/order.tn\"\n!order { id: 1 }");

        assertEquals(List.of("https://example.com/order.tn"), peek.header().imports());
        assertEquals(Optional.empty(), peek.header().id());
        assertFalse(peek.isSchemaDocument());
    }

    @Test
    void readsEveryDirectiveInOrder() {
        TsonDocumentHeader header = peek("""
                !!id:"https://example.com/orders/1"
                !!import:"https://example.com/order.tn"
                !!import:"https://example.com/money.tn"
                !order { id: 1 }
                """);

        assertEquals(Optional.of("https://example.com/orders/1"), header.id());
        assertEquals(List.of("https://example.com/order.tn", "https://example.com/money.tn"), header.imports());
    }

    @Test
    void classifiesASchemaDocumentByItsRootTag() {
        TsonDocumentPeek peek = TsonDocumentPeek.of("""
                !!id:"https://example.com/order.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !schema { order => { id: int32 } }
                """);

        assertTrue(peek.isSchemaDocument());
        assertEquals(List.of("https://tson.io/2026/39/m/core.tn"), peek.header().imports());
        assertEquals(Optional.of("https://example.com/order.tn"), peek.header().id());
    }

    /** An import-free schema is one by its tag alone: the header carries nothing to classify it by. */
    @Test
    void classifiesAnImportFreeSchemaByItsRootTag() {
        assertTrue(TsonDocumentPeek.of("!schema { a => { b: b }  b => {} }").isSchemaDocument());
    }

    /**
     * The tag stands after the root's annotations, so classifying reads past them -- and only them: the schema map
     * that follows, whose sugar no data frame reads, is never parsed.
     */
    @Test
    void classifiesPastTheRootsAnnotationsWithoutReadingTheSchemaMap() {
        TsonDocumentPeek peek = TsonDocumentPeek.of("""
                @doc:"orders" @title:{ text: "Orders" }
                !schema { order => { id: int32 | text  tags: [text; 1..] } }
                """);

        assertTrue(peek.isSchemaDocument());
        assertEquals(null, peek.failure());
    }

    /** A root carrying another tag, or none, is data -- the document's imports say nothing either way. */
    @Test
    void anyOtherRootIsData() {
        assertFalse(TsonDocumentPeek.of("!order { id: 1 }").isSchemaDocument());
        assertFalse(TsonDocumentPeek.of("@doc:\"x\" { id: 1 }").isSchemaDocument());
        assertFalse(TsonDocumentPeek.of("[ 1 2 ]").isSchemaDocument());
    }

    @Test
    void aDocumentWithNoDirectivesHasAnEmptyHeader() {
        TsonDocumentPeek peek = TsonDocumentPeek.of("{ id: 1 }");

        assertEquals(TsonDocumentHeader.NONE, peek.header());
        assertFalse(peek.isSchemaDocument());
    }

    @Test
    void emptyInputHasAnEmptyHeader() {
        assertEquals(TsonDocumentHeader.NONE, peek(""));
    }

    /** No value parsing (§7.1): the header is answerable whether or not what follows it is a document. */
    @Test
    void readsTheHeaderOfADocumentWhoseValueIsMalformed() {
        TsonDocumentHeader header = peek("!!import:\"https://example.com/order.tn\"\n!order { id: ");

        assertEquals(List.of("https://example.com/order.tn"), header.imports());
    }

    @Test
    void readsTheHeaderOfADocumentWithNoValueAtAll() {
        TsonDocumentHeader header = peek("!!import:\"https://example.com/order.tn\"\n");

        assertEquals(List.of("https://example.com/order.tn"), header.imports());
    }

    /** A directive is a directive only in the header: one spelled inside the value is that value's text. */
    @Test
    void doesNotReportADirectiveWrittenInsideTheValue() {
        TsonDocumentHeader header =
                peek("{ note: \"!!import:\\\"https://attacker.example/evil.tn\\\"\" }");

        assertEquals(List.of(), header.imports());
    }

    /** {@code !!import} after the value has started is not a header directive and is not reported as one. */
    @Test
    void doesNotReportADirectiveAfterTheValue() {
        TsonDocumentHeader header = peek("{ id: 1 }\n!!import:\"https://attacker.example/evil.tn\"");

        assertEquals(List.of(), header.imports());
    }

    /** A retired directive is refused by the header grammar, so a peek answers nothing and keeps why. */
    @Test
    void aRetiredDirectiveYieldsNothing() {
        TsonDocumentPeek schema = TsonDocumentPeek.of("!!sch" + "ema:\"https://example.com/order.tn\"\n{ id: 1 }");
        TsonDocumentPeek meta = TsonDocumentPeek.of("!!me" + "ta:\"https://example.com/meta.tn\"\n{ a => {} }");

        assertEquals(TsonDocumentHeader.NONE, schema.header());
        assertTrue(schema.failure().getMessage().contains("'!!schema' directive is retired"), schema.failure()::getMessage);
        assertEquals(TsonDocumentHeader.NONE, meta.header());
        assertFalse(meta.isSchemaDocument());
    }

    /**
     * A header this broken has no answer, and a peek gives none rather than guessing -- the read that
     * follows is where the document earns a real diagnostic.
     */
    @Test
    void aMalformedHeaderYieldsNothingRatherThanGuessing() {
        assertEquals(TsonDocumentHeader.NONE, peek("!!import:\n{ id: 1 }"));
        assertEquals(TsonDocumentHeader.NONE, peek("!!import:\"not a uri at all\"\n{ id: 1 }"));
        assertEquals(TsonDocumentHeader.NONE, peek("!!import:\"https://example.com/order.tn\n{ id: 1 }"));
    }

    /**
     * <b>A malformed header yields nothing at all</b>, not the directives read before the break.
     *
     * <p>§2.2's header has one implementation -- the stream's, which builds {@code DocumentStart} once the
     * whole header has been read -- so a header that fails part-way produces no event and there is no
     * partial answer to hand back. That is the stricter reading of the rule this method already had: what a
     * peek must never do is answer with something the document does not say, and a document whose header is
     * broken has not finished saying anything. A caller wanting the {@code !!id} of a document that does not
     * parse is asking the read for a diagnostic, not the peek for a guess.
     */
    @Test
    void aMalformedDirectiveYieldsNothingEvenAfterAGoodOne() {
        TsonDocumentHeader header =
                peek("!!id:\"https://example.com/orders/1\"\n!!import:\n{ id: 1 }");

        assertEquals(TsonDocumentHeader.NONE, header);
    }

    /** A source that fails is not a verdict on the document, so it is not swallowed as "no header". */
    @Test
    void anIoFailureFromTheSourcePropagates() {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("connection reset");
            }
        };

        assertThrows(UncheckedIOException.class, () -> peek(broken));
    }

    /**
     * The one-shot-stream case: an HTTP body cannot be re-opened, and none is needed -- the peek holds the
     * rest of the document on the stream it already has, so the read that follows sees what it would have
     * seen had no one peeked, without a byte being replayed.
     */
    @Test
    void continuesAOneShotStreamWithoutRewinding() throws Exception {
        String document = "!!id:\"https://example.com/orders/1\"\n"
                + "!!import:\"https://example.com/order.tn\"\n"
                + "{ id: 1  note: \"hello\" }\n";
        TsonDocumentPeek peeked = TsonDocumentPeek.of(oneShot(document));

        assertEquals(List.of("https://example.com/order.tn"), peeked.header().imports());
        assertEquals(Optional.of("https://example.com/orders/1"), peeked.header().id());
        TsonValue value = new TsonTreeReader().read(peeked);
        assertEquals(1, value.get("id").asInt().orElseThrow());
        assertEquals("hello", value.get("note").asString().orElseThrow());
    }

    /** The peek reads the header and no more: a big document is not buffered to classify it. */
    @Test
    void resumingDoesNotBufferTheDocument() throws Exception {
        String body = "x".repeat(500_000);
        String document = "!!import:\"https://example.com/order.tn\"\n{ note: \"" + body + "\" }";
        AtomicLong pulled = new AtomicLong();
        InputStream counting = new FilterInputStream(oneShot(document)) {
            @Override
            public int read() throws IOException {
                int b = super.read();
                if (b >= 0) {
                    pulled.incrementAndGet();
                }
                return b;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                int n = super.read(buffer, offset, length);
                if (n > 0) {
                    pulled.addAndGet(n);
                }
                return n;
            }
        };

        TsonDocumentPeek peeked = TsonDocumentPeek.of(counting);

        assertEquals(List.of("https://example.com/order.tn"), peeked.header().imports());
        assertTrue(pulled.get() < 64_000, "the peek pulled " + pulled.get() + " bytes for a header of 41");

        // And the 500 KB it did not pull is still there to be read, on the same stream.
        assertEquals(body, new TsonTreeReader().read(peeked).get("note").asString().orElseThrow());
    }

    /**
     * A peeked document reads through an ordinary reader, <b>on the same stream</b> -- the header is not
     * replayed and not re-lexed, which is the whole point on a source that cannot be read twice.
     */
    @Test
    void aPeekedDocumentReadsWholeThroughAnOrdinaryReader() throws Exception {
        String document = "!!import:\"https://example.com/order.tn\"\n{ id: 1 }";
        TsonDocumentPeek peeked = TsonDocumentPeek.of(oneShot(document));

        TsonValue value = new TsonTreeReader().read(peeked);

        assertEquals(1, value.get("id").asInt().orElseThrow());
    }

    /** A document that declares no header at all still continues from where the peek left it. */
    @Test
    void continuesADocumentWithNoHeader() throws Exception {
        TsonDocumentPeek peeked = TsonDocumentPeek.of(oneShot("{ id: 1 }"));

        assertEquals(TsonDocumentHeader.NONE, peeked.header());
        assertEquals(1, new TsonTreeReader().read(peeked).get("id").asInt().orElseThrow());
    }

    @Test
    void peeksAnInputStreamWithoutReadingTheDocument() {
        try (InputStream in = stream("!!import:\"https://example.com/order.tn\"\n{ id: 1 }")) {
            assertEquals(List.of("https://example.com/order.tn"), peek(in).imports());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** The type a writer builds and the type a peek returns are the same one, so a round trip is expressible. */
    @Test
    void aWrittenHeaderPeeksBackAsItself() {
        String document = new TsonTreeWriter()
                .identifiedBy("https://example.com/orders/1")
                .describing("https://example.com/order.tn")
                .toTson(TsonAtom.of("hello", "text"));

        TsonDocumentHeader header = peek(document);

        assertEquals(Optional.of("https://example.com/orders/1"), header.id());
        assertEquals(List.of("https://example.com/order.tn"), header.imports());
    }
}
