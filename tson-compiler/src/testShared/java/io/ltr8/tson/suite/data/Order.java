package io.ltr8.tson.suite.data;

/**
 * The host class for the corpus's {@code order} record ({@code schemas/fixtures/meta-picks.tn}), a value of its
 * {@code orders} namespace type, which a {@code ref<order>} slot names by a qualified name.
 */
public record Order(String id) {
}
