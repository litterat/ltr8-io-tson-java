package io.ltr8.tson.base.io;

import java.io.IOException;
import java.io.InputStream;

/**
 * A source drained from an {@code InputStream} -- bytes that are not in memory and may never all be, which
 * is why {@link #resident()} stays empty and a lexer over one keeps its block buffer.
 *
 * <p>The stream is not closed: a caller that opened it owns closing it, and a request body handed here
 * belongs to whoever is serving the request.
 */
final class StreamByteSource implements ByteSource {

    private final InputStream source;
    private final int blockSize;

    StreamByteSource(InputStream source, int blockSize) {
        this.source = source;
        this.blockSize = blockSize;
    }

    @Override
    public byte[] block() {
        return new byte[blockSize];
    }

    @Override
    public int read(byte[] into, int offset, int length) throws IOException {
        return source.read(into, offset, length);
    }
}
