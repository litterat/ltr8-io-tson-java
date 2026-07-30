package io.ltr8.tson.compiler.stream;

import io.ltr8.tson.compiler.Position;

/**
 * {@code "!!schema" ":" single-line-token} (§2.3, §3.3): a scoped-value's schema binding,
 * preserved uninterpreted. Precedes a record field's, map entry's, or array element's own
 * data-value events -- never present at the document root or in map-key position, since those
 * are a plain {@code data-value}, not a {@code scoped-value}.
 */
public record SchemaRef(String uri, Position position) implements TsonEvent {
}
