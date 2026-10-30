package io.ltr8.tson.suite.data.geo;

/**
 * The host class for the corpus's {@code geo.route} record ({@code schemas/fixtures/schema-geo-route.tn}), a member of
 * the schema map nested as {@code geo}: a qualified type name binds to the class of its last segment in a sub-package
 * per earlier segment.
 */
public record Route(String verb, String path) {
}
