package io.ltr8.tson.compiler.stream;

import io.ltr8.tson.compiler.Position;

/**
 * Opens a {@code record} (§2.5). Field order in the stream is preserved and duplicates are not
 * detected here -- resolver-layer concerns, the same deferral {@code ast.RecordValue} documents.
 */
public record RecordStart(Position position) implements TsonEvent {
}
