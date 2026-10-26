package io.ltr8.tson.suite.data;

import java.util.Map;

/**
 * The host class for the corpus's {@code header_set} record, a value of its {@code header_sets} namespace type:
 * methods keyed by {@code text}, a plain map that is no namespace, so a qualified name cannot enter it.
 */
public record HeaderSet(Map<String, Method> headers) {
}
