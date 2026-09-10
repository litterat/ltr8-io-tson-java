package io.ltr8.tson.base.io;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Optional;

/**
 * A source whose bytes are already in memory -- a {@code byte[]}, a heap or direct {@code ByteBuffer}, a
 * mapped file. {@link #resident()} answers, so a lexer indexes the segment and copies nothing.
 *
 * <p>{@link #read} is still implemented, and honestly: a caller that has no use for the segment (a copy of
 * the document, a hash over it) gets the ordinary drain, and the cursor is this source's own so the two
 * are not mixed within one read.
 */
final class SegmentByteSource implements ByteSource {

    private final MemorySegment segment;
    private long position;

    SegmentByteSource(MemorySegment segment) {
        this.segment = segment;
    }

    @Override
    public Optional<MemorySegment> resident() {
        return Optional.of(segment);
    }

    @Override
    public int read(byte[] into, int offset, int length) {
        long remaining = segment.byteSize() - position;
        if (remaining <= 0) {
            return -1;
        }
        int n = (int) Math.min(length, remaining);
        MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, position, into, offset, n);
        position += n;
        return n;
    }
}
