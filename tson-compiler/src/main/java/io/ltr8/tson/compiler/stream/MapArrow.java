package io.ltr8.tson.compiler.stream;

import io.ltr8.tson.compiler.Position;

/** {@code "=>"} (§2.6, §7.2.4): marks the transition from a map entry's key events to its value events. */
public record MapArrow(Position position) implements TsonEvent {
}
