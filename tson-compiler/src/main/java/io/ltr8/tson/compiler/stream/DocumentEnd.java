package io.ltr8.tson.compiler.stream;

import io.ltr8.tson.compiler.Position;

/** Closes the event stream, once the document's root value is complete and only end-of-input remains. */
public record DocumentEnd(Position position) implements TsonEvent {
}
