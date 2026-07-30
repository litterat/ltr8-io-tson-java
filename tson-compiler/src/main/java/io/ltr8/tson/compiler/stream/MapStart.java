package io.ltr8.tson.compiler.stream;

import io.ltr8.tson.compiler.Position;

/**
 * Opens a {@code map} (§2.6). Entry order in the stream is preserved and duplicate keys are not
 * detected or deduplicated here -- resolver-layer concerns, the same deferral {@code
 * ast.MapValue} documents.
 */
public record MapStart(Position position) implements TsonEvent {
}
