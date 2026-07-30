package io.ltr8.tson.compiler.stream;

import io.ltr8.tson.compiler.Position;

/** Closes an {@link ArrayStart}. Elements are back-to-back scoped-value event sequences, no per-element marker. */
public record ArrayEnd(Position position) implements TsonEvent {
}
