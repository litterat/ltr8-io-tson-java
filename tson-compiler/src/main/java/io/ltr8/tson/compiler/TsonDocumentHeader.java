package io.ltr8.tson.compiler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.Optional;

/**
 * A TSON document's header directives ([TSON-DATA] §2.2) -- {@code !!id} plus the one of {@code !!schema} /
 * {@code !!meta} the document carries, the pair that says what a document is before any of its value is read.
 *
 * <p><b>Both ends of the library share this type.</b> {@link #peek(InputStream)} reads a header off source
 * text, stopping the moment §2.2's directives are exhausted; {@link TsonObjectWriter#describing} and {@link
 * TsonTreeWriter#describing} build one to emit. §2.2 makes {@code !!id} the first line when present, and
 * {@link #emit} is the one place that knows it. {@link #NONE} is every writer's default -- a bare value,
 * which is what this library has always written and what every existing consumer of its output expects.
 *
 * <p>A writer only ever produces a <em>data</em> document, so {@link #meta()} is empty on every header a
 * writer holds; it is populated only by {@link #peek}, where it is the whole answer to "is this a schema
 * document?" (§12.1 requires exactly one {@code !!meta}, so its presence decides -- {@link
 * #isSchemaDocument()}).
 *
 * @param id     the document's own identity, or empty
 * @param schema the schema governing the value that follows ({@code !!schema}), or empty
 * @param meta   the meta-schema governing a <em>schema</em> document's declarations ({@code !!meta}), or empty
 */
public record TsonDocumentHeader(Optional<String> id, Optional<String> schema, Optional<String> meta) {

    /** No directives: the writer emits a value and nothing else, and a peeked document declared none. */
    static final TsonDocumentHeader NONE =
            new TsonDocumentHeader(Optional.empty(), Optional.empty(), Optional.empty());

    /**
     * The header of {@code source}, read without parsing (or requiring) a value -- §7.1's "at most two
     * directives of lookahead and no value parsing", which is what lets a stream, preview or content sniffer
     * classify a document from its opening bytes and route it before choosing how to read it.
     *
     * <p>Everything past the header is this method's business only to the extent of not reading it: a
     * document whose <em>value</em> is malformed, or absent entirely, still yields its header. <b>A
     * malformed header does not throw either</b> -- it yields the directives read before it went wrong, and
     * the read that follows is where a malformed document earns its real diagnostic. What a peek must never
     * do is answer with a schema the document does not name, and a total function that sometimes says
     * nothing is how that is guaranteed. (An {@code UncheckedIOException} from the source itself does
     * propagate: that is not a verdict on the document.)
     */
    public static TsonDocumentHeader peek(String source) {
        return TsonDataStream.peekHeader(new TsonDataStream(source));
    }

    /**
     * The header of {@code source}, read as {@link #peek(String)} reads one.
     *
     * <p><b>Reads from {@code source} and does not rewind it</b> -- how far the lexer read is the lexer's
     * business, so what is left is a stream positioned mid-document. For a source that can be read twice (a
     * file re-opened, bytes already in hand) that is the whole story: peek one, read the other. For a source
     * that <em>cannot</em> -- an HTTP request body, a socket, a pipe -- use {@link
     * #peekResumable(InputStream)}, which hands the document back whole. {@code source} is not closed here;
     * a caller that opened it owns closing it.
     */
    public static TsonDocumentHeader peek(InputStream source) {
        return TsonDataStream.peekHeader(new TsonDataStream(source));
    }

    /**
     * The header of {@code source} <b>and the document it came off, whole</b> -- for a one-shot stream,
     * where the routing decision needs the header before the read that consumes the body and there is no
     * second read to be had.
     *
     * <p>Every byte the peek pulls off {@code source} is recorded, and {@link TsonDocumentPeek#document()}
     * replays them ahead of the rest of the stream: the document reads from its very first byte, header
     * directives included, so the reader that follows sees exactly the document a caller who had never
     * peeked would have handed it. Buffered memory is what the lexer read to reach the end of the header --
     * one decoder chunk, in practice -- never the document, which streams through as it always did.
     *
     * <pre>{@code
     * TsonDocumentPeek peeked = TsonDocumentHeader.peekResumable(request.getInputStream());
     * String schema = peeked.header().schema().orElseThrow();
     * TsonValue value = tson.treeReader().withSchema(schema).read(peeked.document());
     * }</pre>
     *
     * <p>Read {@link TsonDocumentPeek#document()} from here on and not {@code source}, which is positioned
     * mid-document.
     */
    public static TsonDocumentPeek peekResumable(InputStream source) {
        RecordingStream recorder = new RecordingStream(source);
        TsonDocumentHeader header = TsonDataStream.peekHeader(new TsonDataStream(recorder));
        return new TsonDocumentPeek(header, recorder.replay());
    }

    /**
     * Everything read off the wrapped source, kept so {@link #replay} can put it back in front of what is
     * left. Recording at the <em>source</em> is what makes the replay exact: the lexer's decoder reads ahead
     * in chunks, so bytes it pulled but never turned into tokens are recorded too, and the reconstructed
     * document is byte-for-byte the original rather than "the original from wherever the tokens stopped".
     */
    private static final class RecordingStream extends InputStream {

        private final InputStream source;
        private final ByteArrayOutputStream seen = new ByteArrayOutputStream();

        RecordingStream(InputStream source) {
            this.source = source;
        }

        @Override
        public int read() throws IOException {
            int b = source.read();
            if (b >= 0) {
                seen.write(b);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = source.read(buffer, offset, length);
            if (n > 0) {
                seen.write(buffer, offset, n);
            }
            return n;
        }

        /** The recorded prefix followed by whatever the source still holds -- closed together. */
        InputStream replay() {
            return new SequenceInputStream(new ByteArrayInputStream(seen.toByteArray()), source);
        }
    }

    /**
     * Whether this is a schema document rather than a data one -- true exactly when {@code !!meta} is
     * present, [TSON-SCHEMA] §12.1 requiring one of every schema document and [TSON-DATA] §2.2 admitting
     * none in a data document.
     */
    public boolean isSchemaDocument() {
        return meta.isPresent();
    }

    TsonDocumentHeader describing(String schemaUri) {
        return new TsonDocumentHeader(id, Optional.of(schemaUri), meta);
    }

    TsonDocumentHeader identifiedBy(String documentId) {
        return new TsonDocumentHeader(Optional.of(documentId), schema, meta);
    }

    /** Writes what this header holds, in §2.2's order. A no-op for {@link #NONE}. */
    void emit(TsonDataEmitter out) {
        id.ifPresent(out::documentId);
        schema.ifPresent(out::schemaRef);
    }
}
