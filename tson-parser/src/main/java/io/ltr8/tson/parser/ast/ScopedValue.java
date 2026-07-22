package io.ltr8.tson.parser.ast;

import java.util.Optional;

/**
 * {@code scoped-value = [ schema-directive ws ] data-value} (§2.3, §7.4): an optional
 * {@code !!schema:"..."} directive followed by a data value. Occurs in exactly three positions —
 * record field values, map entry values, and array elements.
 *
 * <p>{@code schemaRef} is the directive's URI argument, preserved uninterpreted — a Class 1
 * processor "does not act on {@code schema} bindings — it preserves them for the consuming
 * application" (§3.3).
 */
public record ScopedValue(Optional<String> schemaRef, DataValue value) {
}
