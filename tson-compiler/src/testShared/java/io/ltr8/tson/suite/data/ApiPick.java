package io.ltr8.tson.suite.data;

import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.Ref;

/**
 * The host class for the corpus's {@code api_pick} record ({@code schemas/fixtures/link-api-picks.tn}): a value of
 * the namespace type {@code api}, named at a slot typed {@code ref<api>} and delivered when the schema links, and a
 * note.
 */
public record ApiPick(Ref<Namespace<Method>> pick, String note) {
}
