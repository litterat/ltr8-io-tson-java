package io.ltr8.tson.suite.data;

import io.ltr8.tson.schema.meta.TypeRef;

/**
 * The host class for the corpus's {@code setting} record ({@code schemas/fixtures/meta-settings.tn}): the type its
 * {@code kind} names, and its dependent field {@code value}, which holds a value of that type as written.
 */
public record Setting(TypeRef kind, Object value) {
}
