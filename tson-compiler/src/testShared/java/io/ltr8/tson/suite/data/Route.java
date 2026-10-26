package io.ltr8.tson.suite.data;

import io.ltr8.tson.schema.meta.Ref;

/**
 * The host class for the corpus's {@code route} record ({@code schemas/fixtures/meta-api.tn}): a verb and a path,
 * and the method serving them at a slot typed {@code ref<method>}, reached through a namespace entry by a qualified
 * name ({@code handler: myapi.get_orders}) and delivered when the schema links.
 */
public record Route(String verb, String path, Ref<Method> handler) {
}
