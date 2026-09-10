package io.ltr8.tson.base.io;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.InputStream;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two properties, and they are the whole contract: every source yields the same bytes through {@link
 * ByteSource#read}, and a source whose bytes are already in memory says so through {@link
 * ByteSource#resident()} -- which is what lets a lexer index them instead of copying them.
 */
class ByteSourceTest {

    private static final String TEXT = "{ name: \"Ada\"  ok: true }";
    private static final byte[] BYTES = TEXT.getBytes(StandardCharsets.UTF_8);

    /** Drains {@code source} the way a lexer does -- a block at a time, never assuming a full read. */
    private static byte[] drain(ByteSource source) throws IOException {
        byte[] out = new byte[BYTES.length * 2];
        int total = 0;
        for (int n; (n = source.read(out, total, 7)) > 0; ) {   // 7: forces the partial-read path
            total += n;
        }
        byte[] exact = new byte[total];
        System.arraycopy(out, 0, exact, 0, total);
        return exact;
    }

    @Test
    void everySourceYieldsTheSameBytes() throws IOException {
        assertArrayEquals(BYTES, drain(ByteSource.of(TEXT)));
        assertArrayEquals(BYTES, drain(ByteSource.of(BYTES)));
        assertArrayEquals(BYTES, drain(ByteSource.of(ByteBuffer.wrap(BYTES))));
        assertArrayEquals(BYTES, drain(ByteSource.of(MemorySegment.ofArray(BYTES))));
        assertArrayEquals(BYTES, drain(ByteSource.of(new ByteArrayInputStream(BYTES))));
    }

    /** A direct buffer has no array to hand back, which is why the resident answer is a segment. */
    @Test
    void aDirectBufferIsResidentAndReadsTheSame() throws IOException {
        ByteBuffer direct = ByteBuffer.allocateDirect(BYTES.length);
        direct.put(BYTES).flip();

        ByteSource source = ByteSource.of(direct);

        assertTrue(source.resident().isPresent());
        assertArrayEquals(BYTES, drain(source));
    }

    @Test
    void bytesAlreadyInMemoryAreResident() {
        assertTrue(ByteSource.of(TEXT).resident().isPresent());
        assertTrue(ByteSource.of(BYTES).resident().isPresent());
        assertTrue(ByteSource.of(ByteBuffer.wrap(BYTES)).resident().isPresent());
        assertTrue(ByteSource.of(MemorySegment.ofArray(BYTES)).resident().isPresent());
    }

    /** A stream is not resident: its bytes may never all exist at once, which is the case the buffer is for. */
    @Test
    void aStreamIsNotResident() {
        assertEquals(Optional.empty(), ByteSource.of(new ByteArrayInputStream(BYTES)).resident());
    }

    /** The resident segment is the whole input from its first byte -- what a lexer indexes against. */
    @Test
    void theResidentSegmentIsTheWholeInput() {
        MemorySegment segment = ByteSource.of(TEXT).resident().orElseThrow();

        assertEquals(BYTES.length, segment.byteSize());
        assertArrayEquals(BYTES, segment.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE));
    }

    @Test
    void aSliceReadsOnlyItsOwnBytes() throws IOException {
        assertArrayEquals("name".getBytes(StandardCharsets.UTF_8),
                drain(ByteSource.of(BYTES, 2, 4)));
    }

    /**
     * <b>The block is the source's to size</b>, so a deployment tuning against its transport or page size
     * says so once, and both encodings' lexers take it from here rather than each hard-coding a number.
     */
    @Test
    void theBlockSizeIsTheSourcesToChoose() throws IOException {
        assertEquals(ByteSource.DEFAULT_BLOCK_SIZE,
                ByteSource.of(new ByteArrayInputStream(BYTES)).block().length);
        assertEquals(4096,
                ByteSource.of(new ByteArrayInputStream(BYTES), 4096).block().length);

        // And it changes nothing about what is read.
        assertArrayEquals(BYTES, drain(ByteSource.of(new ByteArrayInputStream(BYTES), 3)));
    }

    /** A block that could not hold a byte is refused rather than looping forever on a zero-length read. */
    @Test
    void aBlockSizeBelowOneIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> ByteSource.of(new ByteArrayInputStream(BYTES), 0));
    }

    /**
     * <b>Closing releases what the source acquired, and nothing it was handed.</b> A stream the caller
     * opened stays open -- the convention every entry point in this library states -- so a source is safe
     * to close whether or not it owns anything.
     */
    @Test
    void closingDoesNotCloseAStreamItWasHanded() throws IOException {
        AtomicBoolean closed = new AtomicBoolean();
        InputStream theirs = new ByteArrayInputStream(BYTES) {
            @Override
            public void close() {
                closed.set(true);
            }
        };

        ByteSource.of(theirs).close();

        assertFalse(closed.get(), "of(InputStream) must not close a stream it was handed");
    }

    /** And the one factory that opens a stream itself closes that one -- otherwise it leaks a descriptor. */
    @Test
    void closingClosesAStreamItOpenedItself() throws IOException {
        Path file = Files.createTempFile("byte-source-close", ".tn");
        try {
            Files.write(file, BYTES);
            ByteSource source = ByteSource.of(file);

            assertArrayEquals(BYTES, drain(source));
            source.close();

            // Closing twice is not an error, and the file is releasable on every platform afterwards.
            source.close();
            assertTrue(Files.deleteIfExists(file));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** A file read as a stream owns no memory past the read; mapping it is the opt-in. */
    @Test
    void aPathReadsAsAStreamAndMapsOnRequest() throws IOException {
        Path file = Files.createTempFile("byte-source", ".tn");
        try {
            Files.write(file, BYTES);

            assertFalse(ByteSource.of(file).resident().isPresent(), "of(Path) streams");
            assertArrayEquals(BYTES, drain(ByteSource.of(file)));

            try (Arena arena = Arena.ofConfined()) {
                ByteSource mapped = ByteSource.mapped(file, arena);
                assertTrue(mapped.resident().isPresent(), "mapped(Path, Arena) is resident");
                assertArrayEquals(BYTES, drain(mapped));
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
