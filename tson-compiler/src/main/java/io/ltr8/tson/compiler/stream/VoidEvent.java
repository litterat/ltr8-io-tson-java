package io.ltr8.tson.compiler.stream;

import io.ltr8.tson.compiler.Position;

/** {@code "_"} (§2.9): the explicitly-void sentinel, distinct from any typed value including base-type null. */
public record VoidEvent(Position position) implements TsonEvent {
}
