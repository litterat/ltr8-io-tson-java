package io.ltr8.tson.suite.data;

import io.ltr8.tson.schema.meta.Type;

/**
 * The host class for the corpus's {@code described} record ({@code schemas/fixtures/validate-type-valued-field.tn}): a
 * name, and a type value held as data at a slot typed by the kernel's {@code meta.type}.
 */
public record Described(String name, Type shape) {
}
