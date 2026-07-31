package io.ltr8.tson.compiler.stream;

import java.util.Iterator;

/**
 * A pull-based {@link TsonEvent} source with one token of lookahead -- the contract the
 * schema-compiled reader package ({@code io.ltr8.tson.compiler.reader}) consumes directly, rather
 * than depending on the concrete {@code TsonDataStream}. {@code TsonDataStream} itself is the one
 * real implementation reading actual source text; a caller with no real document to stream from
 * (e.g. resolving a schema's own {@code REQUIRED_DEFAULT}/{@code REQUIRED_FIXED} literal value at
 * compile time, before any real read is in progress) can satisfy this contract with a trivial,
 * synthetic implementation instead, without either side needing to know about the other.
 */
public interface TsonEventSource extends Iterator<TsonEvent> {

    /** The next event, without consuming it -- repeated calls with no intervening {@link #next()} return the same event. */
    TsonEvent peek();
}
