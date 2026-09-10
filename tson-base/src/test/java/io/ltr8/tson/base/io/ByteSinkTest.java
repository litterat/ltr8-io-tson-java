package io.ltr8.tson.base.io;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ByteSource}'s counterpart, and the same ownership rule: closing releases what the sink acquired
 * and nothing it was handed. The rule is what makes a sink safe to close unconditionally, which is what a
 * writer building one needs.
 */
class ByteSinkTest {

    private static final byte[] BYTES = "{ id: 1 }".getBytes(StandardCharsets.UTF_8);

    @Test
    void everySinkTakesTheSameBytes() throws IOException {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        ByteSink.of(stream).write(BYTES, 0, BYTES.length);
        assertArrayEquals(BYTES, stream.toByteArray());

        ByteBuffer buffer = ByteBuffer.allocate(64);
        ByteSink.of(buffer).write(BYTES, 0, BYTES.length);
        buffer.flip();
        byte[] got = new byte[buffer.remaining()];
        buffer.get(got);
        assertArrayEquals(BYTES, got);
    }

    /** A stream the caller opened is the caller\u0027s -- the convention every entry point here states. */
    @Test
    void closingDoesNotCloseAStreamItWasHanded() {
        AtomicBoolean closed = new AtomicBoolean();
        OutputStream theirs = new ByteArrayOutputStream() {
            @Override
            public void close() {
                closed.set(true);
            }
        };

        ByteSink.of(theirs).close();

        assertFalse(closed.get(), "of(OutputStream) must not close a stream it was handed");
    }

    /** And the one factory that opens a stream itself closes that one, or it leaks a descriptor. */
    @Test
    void closingClosesAStreamItOpenedItself() throws IOException {
        Path file = Files.createTempFile("byte-sink", ".tn");
        try {
            ByteSink sink = ByteSink.of(file);
            sink.write(BYTES, 0, BYTES.length);
            sink.flush();
            sink.close();
            sink.close();   // idempotent

            assertArrayEquals(BYTES, Files.readAllBytes(file));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /**
     * <b>Closing is not flushing.</b> A sink cannot tell a caller who finished from one who abandoned the
     * document part-written, so it never pushes on their behalf -- which is why every writer flushes.
     */
    @Test
    void closingDoesNotFlush() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Utf8Sink encoder = Utf8Sink.over(ByteSink.of(out));

        encoder.append("hello");
        assertEquals(0, out.size());

        encoder.flush();
        assertEquals(5, out.size(), "flush is what writes; close would not have");
    }

    /** The block is the sink\u0027s to size, as it is the source\u0027s -- the same pooling seam, both directions. */
    @Test
    void theBlockSizeIsTheSinksToChoose() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        assertEquals(ByteSource.DEFAULT_BLOCK_SIZE, ByteSink.of(out).block().length);
        assertEquals(4096, ByteSink.of(out, 4096).block().length);
        assertThrows(IllegalArgumentException.class, () -> ByteSink.of(out, 0));
    }

    /** A sink built over a path writes a whole document through the encoder, block boundaries included. */
    @Test
    void writesAWholeDocumentToAFile() throws IOException {
        Path file = Files.createTempFile("byte-sink-doc", ".tn");
        try {
            String text = "{ note: \"h\\u00e9llo\" } ".repeat(400);
            try (ByteSink sink = ByteSink.of(file, 64)) {
                Utf8Sink encoder = Utf8Sink.over(sink);
                encoder.append(text);
                encoder.flush();
            }

            assertArrayEquals(text.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(file));
            assertTrue(Files.size(file) > 64, "more than one block, so the drain path ran");
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
