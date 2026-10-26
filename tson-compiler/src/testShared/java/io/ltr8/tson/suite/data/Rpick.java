package io.ltr8.tson.suite.data;

import io.ltr8.tson.schema.meta.Ref;

/**
 * The host class for the corpus's {@code rpick} record ({@code schemas/fixtures/meta-picks.tn}): an {@link Order}
 * named at a slot typed {@code ref<order>} ({@code m: os.o1}) and delivered when the schema links.
 */
public record Rpick(Ref<Order> m) {
}
