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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TsonDocumentHeader#peek}: §7.1's classification from a document's opening bytes.
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
    void readsTheSchemaADataDocumentNames() {
        TsonDocumentHeader header = peek("!!schema:\"https://example.com/order.tn\"\n{ id: 1 }");

        assertEquals(Optional.of("https://example.com/order.tn"), header.schema());
        assertEquals(Optional.empty(), header.id());
        assertFalse(header.isSchemaDocument());
    }

    @Test
    void readsBothDirectivesInOrder() {
        TsonDocumentHeader header = peek("""
                !!id:"https://example.com/orders/1"
                !!schema:"https://example.com/order.tn"
                { id: 1 }
                """);

        assertEquals(Optional.of("https://example.com/orders/1"), header.id());
        assertEquals(Optional.of("https://example.com/order.tn"), header.schema());
    }

    @Test
    void classifiesASchemaDocumentByItsMeta() {
        TsonDocumentHeader header = peek("""
                !!id:"https://example.com/order.tn"
                !!meta:"https://tson.io/2026/36/m/meta.tn"
                !!import:"https://tson.io/2026/36/m/core.tn"
                { order => { id: int32 } }
                """);

        assertTrue(header.isSchemaDocument());
        assertEquals(Optional.of("https://tson.io/2026/36/m/meta.tn"), header.meta());
        assertEquals(Optional.of("https://example.com/order.tn"), header.id());
        assertEquals(Optional.empty(), header.schema());
    }

    @Test
    void aDocumentWithNoDirectivesHasAnEmptyHeader() {
        TsonDocumentHeader header = peek("{ id: 1 }");

        assertEquals(Optional.empty(), header.id());
        assertEquals(Optional.empty(), header.schema());
        assertEquals(Optional.empty(), header.meta());
        assertFalse(header.isSchemaDocument());
    }

    @Test
    void emptyInputHasAnEmptyHeader() {
        assertEquals(TsonDocumentHeader.NONE, peek(""));
    }

    /** No value parsing (§7.1): the header is answerable whether or not what follows it is a document. */
    @Test
    void readsTheHeaderOfADocumentWhoseValueIsMalformed() {
        TsonDocumentHeader header = peek("!!schema:\"https://example.com/order.tn\"\n{ id: ");

        assertEquals(Optional.of("https://example.com/order.tn"), header.schema());
    }

    @Test
    void readsTheHeaderOfADocumentWithNoValueAtAll() {
        TsonDocumentHeader header = peek("!!schema:\"https://example.com/order.tn\"\n");

        assertEquals(Optional.of("https://example.com/order.tn"), header.schema());
    }

    /** A directive is a directive only in the header: one spelled inside the value is that value's text. */
    @Test
    void doesNotReportADirectiveWrittenInsideTheValue() {
        TsonDocumentHeader header =
                peek("{ note: \"!!schema:\\\"https://attacker.example/evil.tn\\\"\" }");

        assertEquals(Optional.empty(), header.schema());
    }

    /** {@code !!schema} after the value has started is not a header directive and is not reported as one. */
    @Test
    void doesNotReportADirectiveAfterTheValue() {
        TsonDocumentHeader header = peek("{ id: 1 }\n!!schema:\"https://attacker.example/evil.tn\"");

        assertEquals(Optional.empty(), header.schema());
    }

    /** A directive §2.2 does not admit here stops the scan; whether the document parses is the parser's answer. */
    @Test
    void stopsAtADirectiveThatIsNeitherSchemaNorMeta() {
        TsonDocumentHeader header = peek("!!import:\"https://example.com/core.tn\"\n{ id: 1 }");

        assertEquals(TsonDocumentHeader.NONE, header);
    }

    /**
     * A header this broken has no answer, and a peek gives none rather than guessing -- the read that
     * follows is where the document earns a real diagnostic.
     */
    @Test
    void aMalformedHeaderYieldsNothingRatherThanGuessing() {
        assertEquals(Optional.empty(), peek("!!schema:\n{ id: 1 }").schema());
        assertEquals(Optional.empty(),
                peek("!!schema:\"not a uri at all\"\n{ id: 1 }").schema());
        assertEquals(Optional.empty(),
                peek("!!schema:\"https://example.com/order.tn\n{ id: 1 }").schema());
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
                peek("!!id:\"https://example.com/orders/1\"\n!!schema:\n{ id: 1 }");

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
                + "!!schema:\"https://example.com/order.tn\"\n"
                + "{ id: 1  note: \"hello\" }\n";
        TsonDocumentPeek peeked = TsonDocumentPeek.of(oneShot(document));

        assertEquals(Optional.of("https://example.com/order.tn"), peeked.header().schema());
        assertEquals(Optional.of("https://example.com/orders/1"), peeked.header().id());
        TsonValue value = new TsonTreeReader().read(peeked);
        assertEquals(1, value.get("id").asInt().orElseThrow());
        assertEquals("hello", value.get("note").asString().orElseThrow());
    }

    /** The peek reads the header and no more: a big document is not buffered to classify it. */
    @Test
    void resumingDoesNotBufferTheDocument() throws Exception {
        String body = "x".repeat(500_000);
        String document = "!!schema:\"https://example.com/order.tn\"\n{ note: \"" + body + "\" }";
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

        assertEquals(Optional.of("https://example.com/order.tn"), peeked.header().schema());
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
        String document = "!!schema:\"https://example.com/order.tn\"\n{ id: 1 }";
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
        try (InputStream in = stream("!!schema:\"https://example.com/order.tn\"\n{ id: 1 }")) {
            assertEquals(Optional.of("https://example.com/order.tn"), peek(in).schema());
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
        assertEquals(Optional.of("https://example.com/order.tn"), header.schema());
    }
}
