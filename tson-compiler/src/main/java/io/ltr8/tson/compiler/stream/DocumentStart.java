package io.ltr8.tson.compiler.stream;

import io.ltr8.tson.compiler.Position;

import java.util.Optional;

/**
 * Opens the event stream: the document's header directives (§2.2) -- {@code id}/{@code schema},
 * the raw URI arguments, uninterpreted -- exactly as {@code io.ltr8.tson.compiler.ast.Document}
 * carries them. Always the first event; {@code !!meta} in the header is rejected (with {@code
 * io.ltr8.tson.compiler.TsonUnsupportedDocumentException}) before this or any other event is
 * ever produced.
 */
public record DocumentStart(Optional<String> id, Optional<String> schema, Position position) implements TsonEvent {
}
