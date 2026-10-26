package io.ltr8.tson.suite.data;

import io.ltr8.tson.schema.meta.TypeRef;

/**
 * The host class for the corpus's {@code pick} record ({@code schemas/fixtures/meta-picks.tn}): a record type of the
 * governed schema, named at a slot typed {@code kind<record>}.
 */
public record Pick(TypeRef t) {
}
