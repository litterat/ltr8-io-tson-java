package io.ltr8.tson.base.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;

/**
 * Where a document's bytes go -- {@link ByteSource}'s counterpart, and the byte half of the write side.
 *
 * <p><b>The char half stays.</b> A writer's two targets are not one thing spelled twice the way a source's
 * were: {@code Appendable} is characters and is what {@code toTson} over a {@code StringBuilder} wants,
 * where this is bytes and is what a stream, a buffer or a socket wants. Routing the first through the
 * second would encode to UTF-8 and decode straight back. So both exist, and each is used where it fits.
 *
 * <p><b>Closing releases what the sink acquired, and nothing it was handed</b> -- {@link ByteSource}'s rule
 * exactly, and for the same reason rather than for symmetry: {@link #of(OutputStream)} closes nothing
 * because the caller opened the stream, {@link #of(Path)} closes the stream it opened itself, a pooling
 * sink returns its {@link #block()}, and a {@code ByteBuffer} has nothing to release. Whoever creates a
 * sink closes it.
 *
 * <p><b>{@link #flush()} is the separate obligation, and the load-bearing one for output.</b> Bytes sit in
 * a block until they are pushed, so a document that is never flushed is a document never written -- which
 * is why every writer here flushes explicitly rather than relying on a close to do it. Closing without
 * flushing is a bug this interface cannot prevent; flushing without closing merely leaks.
 */
public interface ByteSink extends AutoCloseable {

    /**
     * The block a writer encodes into before handing bytes over -- {@link ByteSource#block()}'s counterpart,
     * and the same argument: the size belongs to where the bytes are going, and it is the one allocation on
     * the write path proportional to nothing at all, so it is where a pool would go.
     */
    default byte[] block() {
        return new byte[ByteSource.DEFAULT_BLOCK_SIZE];
    }

    /** Writes {@code length} bytes of {@code from} starting at {@code offset}. */
    void write(byte[] from, int offset, int length) throws IOException;

    /** Pushes anything buffered at this sink onward. Does not close. */
    default void flush() throws IOException {
    }

    /**
     * Releases whatever this sink acquired -- a stream it opened, a pooled {@link #block()} it handed out.
     * Never what it was handed: closing {@link #of(OutputStream)} leaves that stream open.
     *
     * <p>Declared to throw nothing, so try-with-resources needs no catch, and a release that genuinely
     * fails reports it as an {@link UncheckedIOException}. <b>Not a substitute for {@link #flush()}</b>:
     * this does not push buffered bytes, because a sink cannot know whether a caller that stopped writing
     * meant to finish or abandoned the document part-written.
     */
    @Override
    default void close() {
    }

    /** Writes to {@code out}, flushing to it and <b>never closing it</b> -- the caller opened it. */
    static ByteSink of(OutputStream out) {
        return of(out, ByteSource.DEFAULT_BLOCK_SIZE);
    }

    /**
     * {@link #of(OutputStream)} encoding through a block of {@code blockSize} bytes -- for a deployment
     * matching its transport, and {@link ByteSource#of(InputStream, int)}'s counterpart.
     */
    static ByteSink of(OutputStream out, int blockSize) {
        Objects.requireNonNull(out, "out");
        if (blockSize < 1) {
            throw new IllegalArgumentException("blockSize must be at least 1, not " + blockSize);
        }
        return new StreamByteSink(out, blockSize, false);
    }

    /**
     * Writes to {@code file}, creating or truncating it.
     *
     * <p><b>This sink opened the stream, so closing it closes the stream</b> -- the one factory here that
     * acquires anything, and the reason {@code close()} exists. A caller who does not close it leaks a file
     * descriptor; a caller who does not {@link #flush()} first loses the document.
     *
     * @throws UncheckedIOException if the file cannot be opened for writing
     */
    static ByteSink of(Path file) {
        return of(file, ByteSource.DEFAULT_BLOCK_SIZE);
    }

    /** {@link #of(Path)} encoding through a block of {@code blockSize} bytes. */
    static ByteSink of(Path file, int blockSize) {
        if (blockSize < 1) {
            throw new IllegalArgumentException("blockSize must be at least 1, not " + blockSize);
        }
        try {
            return new StreamByteSink(Files.newOutputStream(file), blockSize, true);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }

    /**
     * Writes into {@code buffer} at its own position, which advances -- so a caller reads back what was
     * written by flipping it. A document larger than the buffer's remaining space overflows it, which is
     * the buffer's own contract and not something this hides.
     */
    static ByteSink of(ByteBuffer buffer) {
        return new BufferByteSink(Objects.requireNonNull(buffer, "buffer"));
    }

    /** Writes to {@code channel}, never closing it. */
    static ByteSink of(WritableByteChannel channel) {
        return of(Channels.newOutputStream(Objects.requireNonNull(channel, "channel")));
    }
}
