package io.ltr8.tson.compiler.stream;

import io.ltr8.tson.compiler.Position;

/** Closes an {@link AnnotationStart}. */
public record AnnotationEnd(Position position) implements TsonEvent {
}
