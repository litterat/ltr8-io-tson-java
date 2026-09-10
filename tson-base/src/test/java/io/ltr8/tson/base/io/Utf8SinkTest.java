package io.ltr8.tson.base.io;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The encoder a writer holds instead of an {@code OutputStreamWriter}. Two things to pin: it agrees with
 * the JDK on every text that <em>is</em> valid Unicode, and it disagrees deliberately on the text that is
 * not -- where the JDK writes {@code ?} and this refuses.
 *
 * <p>Every non-ASCII character here is a {@code \\uXXXX} escape rather than a literal, which is this
 * project's standing rule for source and tests: an invisible or confusable character in a file is an
 * editing hazard, and this file is entirely about characters that are easy to get wrong.
 */
class Utf8SinkTest {

    private static byte[] written(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Utf8Sink sink = Utf8Sink.over(ByteSink.of(out));
        sink.append(text);
        sink.flush();
        return out.toByteArray();
    }

    /** Every encoded length, and the boundary either side of each -- 1, 2, 3 and 4 bytes. */
    @Test
    void agreesWithTheJdkOnValidText() {
        for (String text : new String[]{
                "", "a", "{ id: 1 }",
                "\u007F", "\u0080",              // last 1-byte, first 2-byte
                "\u07FF", "\u0800",              // last 2-byte, first 3-byte
                "\uFFFD",                        // 3-byte, near the top of the BMP
                "h\u00E9llo w\u00F6rld",
                "\u65E5\u672C\u8A9E",            // CJK
                "\uD83D\uDE00",                  // 4-byte: the first astral pair
                "a\uD83D\uDE00b",                // a pair between BMP characters
                "\uD83D\uDE00\uD83D\uDE01"}) {   // two pairs adjacent
            assertArrayEquals(text.getBytes(StandardCharsets.UTF_8), written(text),
                    () -> "differs from the JDK on a text of " + text.length() + " chars");
        }
    }

    /** Longer than any one block, so the drain path is exercised rather than assumed. */
    @Test
    void spansManyBlocks() {
        String text = "h\u00E9llo \uD83D\uDE00 ".repeat(500);

        assertArrayEquals(text.getBytes(StandardCharsets.UTF_8), written(text));
    }

    /**
     * A pair split across two {@code append(char)} calls, which is what a char-at-a-time emitter actually
     * produces -- and the reason a high surrogate is held rather than encoded where it stands.
     */
    @Test
    void pairsASurrogateSplitAcrossTwoAppends() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Utf8Sink sink = Utf8Sink.over(ByteSink.of(out));

        sink.append('\uD83D');
        sink.append('\uDE00');
        sink.flush();

        assertArrayEquals("\uD83D\uDE00".getBytes(StandardCharsets.UTF_8), out.toByteArray());
    }

    /**
     * <b>The deliberate disagreement.</b> The JDK encodes a lone surrogate as {@code ?} -- a character
     * nobody wrote, appearing in a document whose identity may be a hash of its bytes. This refuses.
     */
    @Test
    void refusesAnUnpairedSurrogateWhereTheJdkSubstitutes() {
        assertArrayEquals(new byte[]{'?'}, "\uD83D".getBytes(StandardCharsets.UTF_8), "the JDK substitutes");

        assertThrows(IllegalArgumentException.class, () -> written("\uD83D"));    // high, then end of text
        assertThrows(IllegalArgumentException.class, () -> written("\uD83Dx"));   // high, then not a low
        assertThrows(IllegalArgumentException.class, () -> written("\uDE00"));    // a low on its own
    }

    /** Nothing reaches the sink until it is flushed, which is why every writer flushes. */
    @Test
    void bytesArriveOnFlush() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Utf8Sink sink = Utf8Sink.over(ByteSink.of(out));

        sink.append("hello");
        assertEquals(0, out.size(), "a short document sits in the block until flushed");

        sink.flush();
        assertEquals(5, out.size());
    }

    /** A sink is a sink: the same bytes reach a buffer as reach a stream. */
    @Test
    void writesToAByteBufferToo() {
        ByteBuffer buffer = ByteBuffer.allocate(64);
        Utf8Sink sink = Utf8Sink.over(ByteSink.of(buffer));

        sink.append("h\u00E9llo");
        sink.flush();

        byte[] got = new byte[buffer.position()];
        buffer.flip();
        buffer.get(got);
        assertArrayEquals("h\u00E9llo".getBytes(StandardCharsets.UTF_8), got);
    }
}
