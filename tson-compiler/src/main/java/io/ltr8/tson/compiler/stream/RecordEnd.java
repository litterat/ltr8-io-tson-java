package io.ltr8.tson.compiler.stream;

import io.ltr8.tson.compiler.Position;

/** Closes a {@link RecordStart}. */
public record RecordEnd(Position position) implements TsonEvent {
}
