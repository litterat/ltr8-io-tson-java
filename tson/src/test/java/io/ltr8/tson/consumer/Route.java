package io.ltr8.tson.consumer;

import io.ltr8.tson.schema.meta.Ref;

/** A consumer's {@code route} record: the endpoint it serves, at a slot typed {@code ref<endpoint>}, and its verb. */
public record Route(Ref<Endpoint> endpoint, String verb) {
}
