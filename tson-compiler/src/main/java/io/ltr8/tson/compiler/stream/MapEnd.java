package io.ltr8.tson.compiler.stream;

import io.ltr8.tson.compiler.Position;

/** Closes a {@link MapStart}. */
public record MapEnd(Position position) implements TsonEvent {
}
