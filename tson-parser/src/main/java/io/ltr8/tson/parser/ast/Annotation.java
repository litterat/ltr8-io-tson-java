package io.ltr8.tson.parser.ast;

import java.util.Optional;

/**
 * {@code annotation = "@" unquoted-token [ ":" data-value ]} (§3.1, §7.4).
 *
 * <p>Preserved as ordered, uninterpreted metadata — a Class 1 processor "MUST preserve
 * annotations without validating them" (§3.1); interpretation is a [TSON-SCHEMA] concern.
 */
public record Annotation(String name, Optional<DataValue> value) {
}
