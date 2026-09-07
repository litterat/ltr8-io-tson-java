package io.ltr8.tson.json.stream;

import java.util.Iterator;

/**
 * A pull-based {@link JsonEvent} source with one event of lookahead -- the contract the layers above
 * consume, rather than the concrete {@link JsonStream}.
 *
 * <p>{@link JsonStream} is the one implementation reading real source text. A caller with no document
 * to stream from can satisfy this contract with a synthetic implementation instead, without either
 * side needing to know about the other -- which is what keeps a test, or a replay of already-read
 * events, from having to fabricate JSON text to drive it.
 *
 * <p>{@link #hasNext()} is true right up to and including {@link JsonEvent.EndOfDocument}, which is
 * an event like any other: a consumer that stops before pulling it has not verified that the document
 * ends where its root value does.
 */
public interface JsonEventSource extends Iterator<JsonEvent> {

    /** The next event without consuming it -- repeated calls with no intervening {@link #next()} return the same event. */
    JsonEvent peek();
}
