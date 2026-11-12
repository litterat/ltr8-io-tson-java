package io.ltr8.tson.consumer;

import io.ltr8.tson.schema.meta.ValueRef;

/** A consumer's {@code route} record: the endpoint it serves, at a slot typed {@code meta.ref<endpoint>}, and its verb. */
public record Route(ValueRef<Endpoint> endpoint, String verb) {
}
