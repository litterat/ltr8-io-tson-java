package io.ltr8.tson.suite.data;

import io.ltr8.annotation.Field;

import java.util.List;
import java.util.Map;

/**
 * The host class for the corpus's {@code service} record, a value of its {@code services} namespace type: the services
 * it extends, by name, and its methods, the {@code interface} its {@code methods} field holds. The wire name {@code
 * extends} is a Java keyword, so the component binds under it by {@link Field}.
 */
public record Service(@Field("extends") List<String> bases, Map<String, Method> methods) {
}
