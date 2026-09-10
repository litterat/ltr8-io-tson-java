package io.ltr8.tson.base.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Where a document's bytes come from -- the one input a lexer takes, in either encoding.
 *
 * <p><b>Bytes, never characters.</b> [TSON-DATA] §7.1 requires a decoder MUST NOT substitute on malformed
 * UTF-8 and §8.1 requires a byte offset in every diagnostic, so a source that has already been decoded --
 * a {@code Reader}, a {@code CharSequence} -- could satisfy neither: the substitution would have happened
 * under someone else's rules and the offset would be a guess. {@link #of(String)} is admitted because a
 * {@code String} re-encodes to UTF-8 as a value, leaving the offset exact.
 *
 * <p><b>One abstraction for both encodings.</b> [TSON-JSON] §3.1 makes the JSON lexer decode UTF-8 from
 * bytes exactly as [TSON-DATA] §9.1 makes the TSON one, so this is one type by specification rather than
 * by convenience -- the same argument that puts {@code LimitsPolicy} in this module.
 *
 * <p><b>Closing releases what the source acquired, and nothing it was handed.</b> That one rule settles
 * every case: {@link #of(InputStream)} closes nothing, because the caller opened the stream and the whole
 * library says so; {@link #of(Path)} closes the stream it opened itself; a pooling source returns its
 * {@link #block()}; a resident source has nothing to release. So a source is safe to close and safe not to
 * -- and the convention that a handed-in stream is the caller's is preserved exactly, because closing it
 * is a no-op rather than a courtesy someone has to remember not to ask for.
 *
 * <p><b>Whoever creates a source closes it.</b> A reader given one through {@code read(ByteSource)} does
 * not; a reader that built one from a {@code String} or an {@code InputStream} does.
 *
 * <p>{@link #mapped} is the exception that proves the rule: the segment outlives any read, so its lifetime
 * is the caller's {@link Arena} rather than this.
 */
public interface ByteSource extends AutoCloseable {

    /**
     * The block size a reader uses when it must drain this source, absent a reason to differ -- modest
     * because it is throughput, not a lookahead window, so a large document gains nothing from more and a
     * small one should not pay for it.
     */
    int DEFAULT_BLOCK_SIZE = 512;

    /**
     * Fills {@code into} from {@code offset}, returning how many bytes were read, or -1 at end of input --
     * {@link InputStream#read(byte[], int, int)}'s contract, and the path every source supports.
     */
    int read(byte[] into, int offset, int length) throws IOException;

    /**
     * The whole input as one addressable segment, when it is already in memory, and empty when it is not.
     *
     * <p><b>This is the zero-copy path, and it is asked once.</b> A lexer over a resident source indexes
     * the segment directly: no intermediate buffer is allocated and no byte is copied, where a streaming
     * source is drained through {@link #read} into a block at a time. {@link MemorySegment} is what makes
     * one answer serve every resident case -- a {@code byte[]}, a heap or direct {@link ByteBuffer}, a
     * mapped file -- since the off-heap ones have no array to hand back.
     *
     * <p>A source that answers here MUST answer consistently: the segment is the entire input, from its
     * first byte, and does not change under the reader.
     */
    default Optional<MemorySegment> resident() {
        return Optional.empty();
    }

    /**
     * The block a reader drains this source through -- <b>asked once, and only when {@link #resident()} is
     * empty</b>, since a source already in memory is indexed rather than copied.
     *
     * <p>It comes from the source rather than from the reader for two reasons. One is that the size is a
     * property of where the bytes are coming from: a socket, a file and a mapped region want different
     * blocks, and a deployment tuning against its own page size has one place to say so
     * ({@link #of(InputStream, int)}). The other is that this is the only allocation on the read path
     * proportional to nothing at all, so it is the one worth pooling — and a pool has to be owned by
     * whoever knows the block's provenance, which is the source.
     *
     * <p><b>Pooling is not implemented here</b>, and the shape is deliberate rather than half-done: a pool
     * also needs a return path, and a reader has no lifecycle to hang one on today. When it does, the
     * counterpart belongs beside this method, not on the reader.
     */
    default byte[] block() {
        return new byte[DEFAULT_BLOCK_SIZE];
    }

    /**
     * Releases whatever this source acquired -- a stream it opened, a pooled {@link #block()} it handed
     * out. Never what it was handed: closing {@link #of(InputStream)} leaves that stream open.
     *
     * <p>Declared to throw nothing, so try-with-resources over a source needs no catch. A source whose
     * release can genuinely fail reports it as an {@link UncheckedIOException}: by the time a document has
     * been read, a failure to hand a resource back is not a verdict on the document and must not be
     * mistaken for one.
     */
    @Override
    default void close() {
    }

    // ── Resident sources ─────────────────────────────────────────────────

    /** {@code text} as UTF-8. The one encode is unavoidable -- a {@code String} is characters -- and is the last copy. */
    static ByteSource of(String text) {
        return of(text.getBytes(StandardCharsets.UTF_8));
    }

    /** {@code bytes} as they stand. Not copied, and not copied from: a caller that mutates them changes what is read. */
    static ByteSource of(byte[] bytes) {
        return of(MemorySegment.ofArray(Objects.requireNonNull(bytes, "bytes")));
    }

    /** {@code length} bytes of {@code bytes} from {@code offset}. */
    static ByteSource of(byte[] bytes, int offset, int length) {
        return of(MemorySegment.ofArray(bytes).asSlice(offset, length));
    }

    /**
     * {@code buffer}'s remaining bytes, heap or direct. The buffer's own position is not moved -- reading
     * is by index -- so a caller may still use it afterwards.
     */
    static ByteSource of(ByteBuffer buffer) {
        return of(MemorySegment.ofBuffer(Objects.requireNonNull(buffer, "buffer")));
    }

    /** {@code segment} as the whole input -- the shape every other resident factory reduces to. */
    static ByteSource of(MemorySegment segment) {
        return new SegmentByteSource(Objects.requireNonNull(segment, "segment"));
    }

    /**
     * {@code file} mapped into memory for the life of {@code arena} -- read with no heap copy at all, which
     * is what this exists for on a large document.
     *
     * <p>The segment dies with {@code arena}, and reading a source built from a closed arena throws. That
     * obligation is why this is a named factory rather than what {@link #of(Path)} does: a caller who wants
     * mapping asks for it and owns the arena, and everyone else gets a stream.
     */
    static ByteSource mapped(Path file, Arena arena) throws IOException {
        try (FileChannel channel = FileChannel.open(file)) {
            return of(channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena));
        }
    }

    // ── Streaming sources ────────────────────────────────────────────────

    /** {@code source}, drained as it is read through a {@link #DEFAULT_BLOCK_SIZE} block. Not closed here. */
    static ByteSource of(InputStream source) {
        return of(source, DEFAULT_BLOCK_SIZE);
    }

    /**
     * {@link #of(InputStream)} draining through a block of {@code blockSize} bytes -- for a deployment
     * matching its transport or its page size, and the knob a throughput measurement turns.
     */
    static ByteSource of(InputStream source, int blockSize) {
        if (blockSize < 1) {
            throw new IllegalArgumentException("blockSize must be at least 1, not " + blockSize);
        }
        return new StreamByteSource(Objects.requireNonNull(source, "source"), blockSize);
    }

    /** {@code channel}, drained as it is read. Not closed here. */
    static ByteSource of(ReadableByteChannel channel) {
        return of(java.nio.channels.Channels.newInputStream(Objects.requireNonNull(channel, "channel")));
    }

    /**
     * {@code file}, read as a stream -- the safe default, owning no memory past the read. {@link
     * #mapped(Path, Arena)} is the zero-copy form, for a caller willing to hold an arena.
     *
     * @throws UncheckedIOException if the file cannot be opened
     */
    static ByteSource of(Path file) {
        return of(file, DEFAULT_BLOCK_SIZE);
    }

    /**
     * {@link #of(Path)} draining through a block of {@code blockSize} bytes.
     *
     * <p><b>This source opened the stream, so closing it closes the stream</b> -- the one factory here that
     * acquires anything, and the reason {@code close()} exists at all. A caller who does not close it leaks
     * a file descriptor, which is why every reader that builds a source from a path closes it.
     *
     * @throws UncheckedIOException if the file cannot be opened
     */
    static ByteSource of(Path file, int blockSize) {
        try {
            InputStream opened = Files.newInputStream(file);
            ByteSource streaming = of(opened, blockSize);
            return new ByteSource() {
                @Override
                public int read(byte[] into, int offset, int length) throws IOException {
                    return streaming.read(into, offset, length);
                }

                @Override
                public byte[] block() {
                    return streaming.block();
                }

                @Override
                public void close() {
                    try {
                        opened.close();
                    } catch (IOException e) {
                        throw new UncheckedIOException("cannot close " + file, e);
                    }
                }
            };
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }
}
