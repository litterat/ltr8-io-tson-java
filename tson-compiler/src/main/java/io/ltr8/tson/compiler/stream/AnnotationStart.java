package io.ltr8.tson.compiler.stream;

import io.ltr8.tson.compiler.Position;

/**
 * {@code "@" unquoted-token [ ":" data-value ]} (§3.1): opens one annotation. If the annotation
 * carries a value, that value's own event sequence follows immediately; either way an {@link
 * AnnotationEnd} closes it before the next sibling annotation, an optional {@link TypeRef}, or
 * the enclosing value's core-value.
 */
public record AnnotationStart(String name, Position position) implements TsonEvent {
}
