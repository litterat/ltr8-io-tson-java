package io.ltr8.tson.base.io;

import java.io.Flushable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;

/**
 * Characters into a {@link ByteSink}, encoded UTF-8 -- the write-side counterpart of the decoding a lexer
 * does off a {@link ByteSource}, and what a writer holds instead of an {@code OutputStreamWriter}.
 *
 * <p><b>Why not {@code OutputStreamWriter}.</b> Three reasons, and the third is the smallest. It brings its
 * own {@code StreamEncoder} with its own byte and char buffers, sized by the JDK rather than by the sink
 * the bytes are going to -- so a caller tuning a block has one knob here and none there. It cannot write to
 * anything but an {@code OutputStream}, where a sink also covers a {@code ByteBuffer} and a channel. And
 * <b>it substitutes</b>: an unpaired surrogate is silently written as {@code ?}, which is a character
 * nobody wrote appearing in a document whose identity may be a hash of its bytes. This refuses instead.
 *
 * <p><b>A surrogate pair spans two {@link #append(char)} calls</b>, so a high surrogate is held until its
 * low one arrives. One that is never completed -- followed by anything else, or left standing at
 * {@link #flush()} -- is an {@link IllegalArgumentException}: a Java {@code String} can hold a lone
 * surrogate and UTF-8 cannot represent one, so there is nothing to write and nothing to guess.
 *
 * <p>Not thread-safe, and single-use over one sink.
 */
public final class Utf8Sink implements Appendable, Flushable {

    private final ByteSink sink;
    private final byte[] block;
    private int filled;

    /** A pending high surrogate awaiting its low one, or 0. */
    private char pendingHigh;

    private Utf8Sink(ByteSink sink, byte[] block) {
        this.sink = sink;
        this.block = block;
    }

    /** Encodes into {@code sink} through the block {@code sink} chooses. */
    public static Utf8Sink over(ByteSink sink) {
        Objects.requireNonNull(sink, "sink");
        return new Utf8Sink(sink, sink.block());
    }

    @Override
    public Utf8Sink append(CharSequence text) {
        return append(text, 0, text.length());
    }

    @Override
    public Utf8Sink append(CharSequence text, int start, int end) {
        for (int i = start; i < end; i++) {
            append(text.charAt(i));
        }
        return this;
    }

    @Override
    public Utf8Sink append(char c) {
        if (pendingHigh != 0) {
            if (!Character.isLowSurrogate(c)) {
                throw unpaired();
            }
            encode(Character.toCodePoint(pendingHigh, c));
            pendingHigh = 0;
            return this;
        }
        if (Character.isHighSurrogate(c)) {
            pendingHigh = c;
            return this;
        }
        if (Character.isLowSurrogate(c)) {
            throw unpaired();
        }
        encode(c);
        return this;
    }

    /**
     * Pushes everything encoded so far to the sink and flushes it. A high surrogate still waiting for its
     * pair is a failure here rather than a byte quietly dropped.
     */
    @Override
    public void flush() {
        if (pendingHigh != 0) {
            throw unpaired();
        }
        drain();
        try {
            sink.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static IllegalArgumentException unpaired() {
        return new IllegalArgumentException("cannot write an unpaired surrogate as UTF-8: the text being "
                + "written is not valid Unicode, and writing a substitute would put a character in the "
                + "document that nobody wrote");
    }

    private void encode(int cp) {
        if (cp < 0x80) {
            put((byte) cp);
        } else if (cp < 0x800) {
            put((byte) (0xC0 | (cp >> 6)));
            put((byte) (0x80 | (cp & 0x3F)));
        } else if (cp < 0x10000) {
            put((byte) (0xE0 | (cp >> 12)));
            put((byte) (0x80 | ((cp >> 6) & 0x3F)));
            put((byte) (0x80 | (cp & 0x3F)));
        } else {
            put((byte) (0xF0 | (cp >> 18)));
            put((byte) (0x80 | ((cp >> 12) & 0x3F)));
            put((byte) (0x80 | ((cp >> 6) & 0x3F)));
            put((byte) (0x80 | (cp & 0x3F)));
        }
    }

    private void put(byte b) {
        if (filled == block.length) {
            drain();
        }
        block[filled++] = b;
    }

    private void drain() {
        if (filled == 0) {
            return;
        }
        try {
            sink.write(block, 0, filled);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        filled = 0;
    }
}
