package io.ltr8.tson.suite.data;

import java.util.Map;

/**
 * The host class for the corpus's {@code service_resource} record, a value of its {@code service_resources} namespace
 * type: the {@code service_call} family's values by name, in a plain map field.
 */
public record ServiceResource(Map<String, ServiceCall> calls) {
}
