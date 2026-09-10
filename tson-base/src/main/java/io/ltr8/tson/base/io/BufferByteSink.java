package io.ltr8.tson.base.io;

import java.nio.ByteBuffer;

/**
 * A sink writing into a {@code ByteBuffer} at its own position, which advances -- so a caller reads back
 * what was written by flipping it. The write-side peer of a resident {@link ByteSource}: no stream, nothing
 * acquired, and so nothing for {@link #close()} to release.
 *
 * <p>A document larger than the buffer's remaining space overflows it. That is the buffer's own contract
 * and is not hidden here: a sink that silently grew or truncated would be answering a question the caller
 * asked the buffer.
 */
final class BufferByteSink implements ByteSink {

    private final ByteBuffer buffer;

    BufferByteSink(ByteBuffer buffer) {
        this.buffer = buffer;
    }

    @Override
    public void write(byte[] from, int offset, int length) {
        buffer.put(from, offset, length);
    }
}
