package io.ltr8.tson.base.io;

import java.io.IOException;
import java.io.OutputStream;
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
 * <p>Not {@link AutoCloseable}, for {@link ByteSource}'s reason: a sink does not own what it was built
 * over. {@link #flush()} is here because a buffered target that is never flushed writes nothing, which is a
 * failure a caller cannot see; closing is still theirs.
 */
public interface ByteSink {

    /** Writes {@code length} bytes of {@code from} starting at {@code offset}. */
    void write(byte[] from, int offset, int length) throws IOException;

    /** Pushes anything buffered at this sink onward. Does not close. */
    default void flush() throws IOException {
    }

    /** Writes to {@code out}, flushing to it and never closing it. */
    static ByteSink of(OutputStream out) {
        Objects.requireNonNull(out, "out");
        return new ByteSink() {
            @Override
            public void write(byte[] from, int offset, int length) throws IOException {
                out.write(from, offset, length);
            }

            @Override
            public void flush() throws IOException {
                out.flush();
            }
        };
    }

    /**
     * Writes into {@code buffer} at its own position, which advances -- so a caller reads back what was
     * written by flipping it. A document larger than the buffer's remaining space overflows it, which is
     * the buffer's own contract and not something this hides.
     */
    static ByteSink of(ByteBuffer buffer) {
        Objects.requireNonNull(buffer, "buffer");
        return (from, offset, length) -> buffer.put(from, offset, length);
    }

    /** Writes to {@code channel}, never closing it. */
    static ByteSink of(WritableByteChannel channel) {
        return of(Channels.newOutputStream(Objects.requireNonNull(channel, "channel")));
    }
}
