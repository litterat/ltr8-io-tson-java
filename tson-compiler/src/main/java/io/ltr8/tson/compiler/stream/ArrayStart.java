package io.ltr8.tson.compiler.stream;

import io.ltr8.tson.compiler.Position;

/** Opens an {@code array} (§2.7). Never ambiguous with a record/map -- {@code [} is unmistakable. */
public record ArrayStart(Position position) implements TsonEvent {
}
