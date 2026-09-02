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
class TsonDocumentHeaderTest {

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
        TsonDocumentHeader header = TsonDocumentHeader.peek("!!schema:\"https://example.com/order.tn\"\n{ id: 1 }");

        assertEquals(Optional.of("https://example.com/order.tn"), header.schema());
        assertEquals(Optional.empty(), header.id());
        assertFalse(header.isSchemaDocument());
    }

    @Test
    void readsBothDirectivesInOrder() {
        TsonDocumentHeader header = TsonDocumentHeader.peek("""
                !!id:"https://example.com/orders/1"
                !!schema:"https://example.com/order.tn"
                { id: 1 }
                """);

        assertEquals(Optional.of("https://example.com/orders/1"), header.id());
        assertEquals(Optional.of("https://example.com/order.tn"), header.schema());
    }

    @Test
    void classifiesASchemaDocumentByItsMeta() {
        TsonDocumentHeader header = TsonDocumentHeader.peek("""
                !!id:"https://example.com/order.tn"
                !!meta:"https://tson.io/2026/35/m/meta.tn"
                !!import:"https://tson.io/2026/35/m/core.tn"
                { order => { id: int32 } }
                """);

        assertTrue(header.isSchemaDocument());
        assertEquals(Optional.of("https://tson.io/2026/35/m/meta.tn"), header.meta());
        assertEquals(Optional.of("https://example.com/order.tn"), header.id());
        assertEquals(Optional.empty(), header.schema());
    }

    @Test
    void aDocumentWithNoDirectivesHasAnEmptyHeader() {
        TsonDocumentHeader header = TsonDocumentHeader.peek("{ id: 1 }");

        assertEquals(Optional.empty(), header.id());
        assertEquals(Optional.empty(), header.schema());
        assertEquals(Optional.empty(), header.meta());
        assertFalse(header.isSchemaDocument());
    }

    @Test
    void emptyInputHasAnEmptyHeader() {
        assertEquals(TsonDocumentHeader.NONE, TsonDocumentHeader.peek(""));
    }

    /** No value parsing (§7.1): the header is answerable whether or not what follows it is a document. */
    @Test
    void readsTheHeaderOfADocumentWhoseValueIsMalformed() {
        TsonDocumentHeader header = TsonDocumentHeader.peek("!!schema:\"https://example.com/order.tn\"\n{ id: ");

        assertEquals(Optional.of("https://example.com/order.tn"), header.schema());
    }

    @Test
    void readsTheHeaderOfADocumentWithNoValueAtAll() {
        TsonDocumentHeader header = TsonDocumentHeader.peek("!!schema:\"https://example.com/order.tn\"\n");

        assertEquals(Optional.of("https://example.com/order.tn"), header.schema());
    }

    /** A directive is a directive only in the header: one spelled inside the value is that value's text. */
    @Test
    void doesNotReportADirectiveWrittenInsideTheValue() {
        TsonDocumentHeader header =
                TsonDocumentHeader.peek("{ note: \"!!schema:\\\"https://attacker.example/evil.tn\\\"\" }");

        assertEquals(Optional.empty(), header.schema());
    }

    /** {@code !!schema} after the value has started is not a header directive and is not reported as one. */
    @Test
    void doesNotReportADirectiveAfterTheValue() {
        TsonDocumentHeader header = TsonDocumentHeader.peek("{ id: 1 }\n!!schema:\"https://attacker.example/evil.tn\"");

        assertEquals(Optional.empty(), header.schema());
    }

    /** A directive §2.2 does not admit here stops the scan; whether the document parses is the parser's answer. */
    @Test
    void stopsAtADirectiveThatIsNeitherSchemaNorMeta() {
        TsonDocumentHeader header = TsonDocumentHeader.peek("!!import:\"https://example.com/core.tn\"\n{ id: 1 }");

        assertEquals(TsonDocumentHeader.NONE, header);
    }

    /**
     * A header this broken has no answer, and a peek gives none rather than guessing -- the read that
     * follows is where the document earns a real diagnostic.
     */
    @Test
    void aMalformedHeaderYieldsNothingRatherThanGuessing() {
        assertEquals(Optional.empty(), TsonDocumentHeader.peek("!!schema:\n{ id: 1 }").schema());
        assertEquals(Optional.empty(),
                TsonDocumentHeader.peek("!!schema:\"not a uri at all\"\n{ id: 1 }").schema());
        assertEquals(Optional.empty(),
                TsonDocumentHeader.peek("!!schema:\"https://example.com/order.tn\n{ id: 1 }").schema());
    }

    /** The directives read before the broken one still stand -- they are what the document does say. */
    @Test
    void keepsWhatItReadBeforeAMalformedDirective() {
        TsonDocumentHeader header =
                TsonDocumentHeader.peek("!!id:\"https://example.com/orders/1\"\n!!schema:\n{ id: 1 }");

        assertEquals(Optional.of("https://example.com/orders/1"), header.id());
        assertEquals(Optional.empty(), header.schema());
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

        assertThrows(UncheckedIOException.class, () -> TsonDocumentHeader.peek(broken));
    }

    /**
     * The one-shot-stream case: an HTTP body cannot be re-opened, so the peek hands the document back whole
     * -- from its first byte, header directives included -- and the read that follows sees what it would
     * have seen had no one peeked.
     */
    @Test
    void resumesAOneShotStreamFromItsFirstByte() throws Exception {
        String document = "!!id:\"https://example.com/orders/1\"\n"
                + "!!schema:\"https://example.com/order.tn\"\n"
                + "{ id: 1  note: \"hello\" }\n";
        TsonDocumentPeek peeked = TsonDocumentHeader.peekResumable(oneShot(document));

        assertEquals(Optional.of("https://example.com/order.tn"), peeked.header().schema());
        assertEquals(Optional.of("https://example.com/orders/1"), peeked.header().id());
        assertEquals(document, new String(peeked.document().readAllBytes(), StandardCharsets.UTF_8));
    }

    /** Replaying is a prefix in front of the rest, not a buffer of the whole: a big document still streams. */
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

        TsonDocumentPeek peeked = TsonDocumentHeader.peekResumable(counting);

        assertEquals(Optional.of("https://example.com/order.tn"), peeked.header().schema());
        assertTrue(pulled.get() < 64_000, "the peek pulled " + pulled.get() + " bytes for a header of 41");
        assertEquals(document, new String(peeked.document().readAllBytes(), StandardCharsets.UTF_8));
    }

    /** A resumed document reads as a document -- the header the peek consumed is parsed again, by the reader. */
    @Test
    void aResumedDocumentReadsWholeThroughAnOrdinaryReader() throws Exception {
        String document = "!!schema:\"https://example.com/order.tn\"\n{ id: 1 }";
        TsonDocumentPeek peeked = TsonDocumentHeader.peekResumable(oneShot(document));

        TsonValue value = new TsonTreeReader().read(peeked.document());

        assertEquals(1, value.get("id").asInt().orElseThrow());
    }

    /** Nothing to replay when the document declares no header at all. */
    @Test
    void resumesADocumentWithNoHeader() throws Exception {
        TsonDocumentPeek peeked = TsonDocumentHeader.peekResumable(oneShot("{ id: 1 }"));

        assertEquals(TsonDocumentHeader.NONE, peeked.header());
        assertEquals("{ id: 1 }", new String(peeked.document().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void peeksAnInputStreamWithoutReadingTheDocument() {
        try (InputStream in = stream("!!schema:\"https://example.com/order.tn\"\n{ id: 1 }")) {
            assertEquals(Optional.of("https://example.com/order.tn"), TsonDocumentHeader.peek(in).schema());
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

        TsonDocumentHeader header = TsonDocumentHeader.peek(document);

        assertEquals(Optional.of("https://example.com/orders/1"), header.id());
        assertEquals(Optional.of("https://example.com/order.tn"), header.schema());
    }
}
