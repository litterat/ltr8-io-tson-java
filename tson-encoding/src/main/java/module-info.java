/**
 * The join between a linked schema and a deployment's host classes, which both encodings make when a document is
 * read into Java objects: which class each entry binds to ({@code TsonSchemaBinding}), answered once, when a schema
 * is compiled for bind mode, and read by both stacks' readers and by any caller holding a compiled schema.
 *
 * <p><b>A module of its own because neither stack can hold it for the other.</b> {@code tson-json} does not depend on
 * {@code tson-compiler}, so the join left in either stack is that stack's alone; and it cannot go lower, since it
 * names {@code ltr8-bind}'s descriptors, which {@code tson-schema} does not, and the atom vocabulary, which depends on
 * {@code tson-schema}. Which entry a written name is asked as is a fact of the linked schema alone, and stays there
 * ({@code TsonLinkedSchema.bindingCandidates}).
 */
module io.ltr8.tson.encoding {
    exports io.ltr8.tson.encoding;

    requires transitive io.ltr8.tson.schema;
    requires transitive io.ltr8.tson.atom;
    requires transitive io.ltr8.bind;
}
