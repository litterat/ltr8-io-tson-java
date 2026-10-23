package io.ltr8.tson.consumer;

import io.ltr8.tson.schema.meta.TypeRef;

/**
 * A consumer's {@code endpoint} record, a value held under a key of an {@code endpoint_map}: its request record and
 * response type, by name.
 */
public record Endpoint(TypeRef request, TypeRef response) {
}
