package io.ltr8.tson.compiler.stream;

import io.ltr8.tson.compiler.Position;

/**
 * {@code "{" ws "}"} (§2.8): deliberately its own event, not resolved to an empty record or map
 * here -- the same deferral to a later layer that {@code ast.EmptyBrace} documents.
 */
public record EmptyBraceEvent(Position position) implements TsonEvent {
}
