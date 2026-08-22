package io.ltr8.tson.compiler;

import io.ltr8.tson.tree.TsonArray;
import io.ltr8.tson.tree.TsonAtom;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The streaming write surface -- {@code write(value, OutputStream)}/{@code write(value, Appendable)} on both
 * writers, the mirror of {@link TsonObjectReader}/{@link TsonTreeReader} taking an {@code InputStream}. What
 * the ordinary {@code toTson} tests cover is the text; what these cover is that the text never has to exist
 * as one {@code String}, and what happens to the stream around it. The rendering matrix stays in
 * {@code TsonObjectWriterTest}/{@code TsonTreeWriterTest}.
 */
class TsonWriterStreamingTest {

    public record Person(String name, long age) {
    }

    /** Records each write's size and whether the stream was closed -- both are the claims under test. */
    private static final class RecordingStream extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final List<Integer> writes = new ArrayList<>();
        private boolean closed = false;

        @Override
        public void write(int b) {
            writes.add(1);
            bytes.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            writes.add(len);
            bytes.write(b, off, len);
        }

        @Override
        public void close() {
            closed = true;
        }

        String text() {
            return bytes.toString(StandardCharsets.UTF_8);
        }
    }

    @Test
    void anObjectWrittenToAStreamIsTheSameDocumentToTsonProduces() {
        Person value = new Person("Ada", 36);
        RecordingStream out = new RecordingStream();

        new TsonObjectWriter().write(value, out);

        assertEquals(new TsonObjectWriter().toTson(value), out.text());
    }

    @Test
    void aTreeWrittenToAStreamIsTheSameDocumentToTsonProduces() {
        TsonValue node = TsonArray.of(TsonAtom.of("a"), TsonAtom.of(BigInteger.ONE));
        RecordingStream out = new RecordingStream();

        new TsonTreeWriter().write(node, out);

        assertEquals(new TsonTreeWriter().toTson(node), out.text());
    }

    /** UTF-8 ([TSON-DATA] §9.1), not the platform default -- the one thing a stream API can get wrong silently. */
    @Test
    void theBytesAreUtf8() {
        Person value = new Person("Ada Lovelace — éà中", 36);
        RecordingStream out = new RecordingStream();

        new TsonObjectWriter().write(value, out);

        assertArrayEquals(new TsonObjectWriter().toTson(value).getBytes(StandardCharsets.UTF_8), out.bytes.toByteArray(),
                "the stream's bytes are the document's UTF-8 encoding");
    }

    /**
     * Flushed, because an unflushed encoder drops a short document entirely, and <b>not</b> closed, because
     * the caller owns the stream -- writing one document into an HTTP response body is the case this exists
     * for, and closing it there would end the response.
     */
    @Test
    void theStreamIsFlushedAndNotClosed() {
        RecordingStream out = new RecordingStream();

        new TsonObjectWriter().write(new Person("Ada", 36), out);

        assertFalse(out.text().isEmpty(), "flushed: the document reached the stream");
        assertFalse(out.closed, "not closed: the stream belongs to the caller");
    }

    /**
     * The point of the whole surface: a document larger than the encoder's own buffer reaches the stream in
     * pieces <em>while</em> it is being written, so neither the text nor its bytes exist whole in memory.
     */
    @Test
    void aLargeDocumentReachesTheStreamInPiecesRatherThanAllAtTheEnd() {
        List<TsonValue> elements = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            elements.add(TsonAtom.of("element-" + i));
        }
        RecordingStream out = new RecordingStream();

        new TsonTreeWriter().write(TsonArray.of(elements.toArray(new TsonValue[0])), out);

        assertTrue(out.writes.size() > 1,
                "arrived in " + out.writes.size() + " write(s) -- one would mean the document was materialised first");
        assertTrue(out.text().length() > 200_000, "and it is genuinely large: " + out.text().length() + " chars");
    }

    /** An {@link Appendable} a caller already holds -- the same document, no stream involved. */
    @Test
    void anAppendableSinkTakesTheDocumentToo() {
        StringBuilder out = new StringBuilder("prefix: ");

        new TsonObjectWriter().write(new Person("Ada", 36), out);

        assertEquals("prefix: " + new TsonObjectWriter().toTson(new Person("Ada", 36)), out.toString());
    }

    /**
     * A sink that fails is an IO fault, not a verdict on the value: {@link UncheckedIOException}, the same
     * treatment {@code Lexer} gives a failing {@code InputStream} on the read side, rather than
     * {@link TsonWriteException}, which means "this value cannot be written as TSON".
     */
    @Test
    void aFailingSinkIsAnUncheckedIoException() {
        Appendable failing = new Appendable() {
            @Override
            public Appendable append(CharSequence csq) throws IOException {
                throw new IOException("sink is gone");
            }

            @Override
            public Appendable append(CharSequence csq, int start, int end) throws IOException {
                throw new IOException("sink is gone");
            }

            @Override
            public Appendable append(char c) throws IOException {
                throw new IOException("sink is gone");
            }
        };

        UncheckedIOException thrown = assertThrows(UncheckedIOException.class,
                () -> new TsonObjectWriter().write(new Person("Ada", 36), failing));

        assertEquals("sink is gone", thrown.getCause().getMessage());
    }
}
