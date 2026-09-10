package io.ltr8.tson.base.io;

import java.io.IOException;
import java.io.OutputStream;

/**
 * A sink writing to an {@code OutputStream} -- {@link StreamByteSource}'s counterpart, and the shape every
 * other byte target here reduces to.
 *
 * <p><b>Whether closing closes the stream is the constructor's to say</b>, because that is exactly the
 * ownership question: a stream the caller opened is theirs and {@link ByteSink#of(OutputStream)} leaves it
 * open, where the stream {@link ByteSink#of(java.nio.file.Path)} opened is this sink's and closing releases
 * it. One class, one flag, rather than two nearly identical ones.
 */
final class StreamByteSink implements ByteSink {

    private final OutputStream out;
    private final int blockSize;
    private final boolean owned;

    StreamByteSink(OutputStream out, int blockSize, boolean owned) {
        this.out = out;
        this.blockSize = blockSize;
        this.owned = owned;
    }

    @Override
    public byte[] block() {
        return new byte[blockSize];
    }

    @Override
    public void write(byte[] from, int offset, int length) throws IOException {
        out.write(from, offset, length);
    }

    @Override
    public void flush() throws IOException {
        out.flush();
    }

    @Override
    public void close() {
        if (!owned) {
            return;
        }
        try {
            out.close();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("cannot close the stream this sink opened", e);
        }
    }
}
