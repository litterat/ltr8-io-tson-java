package io.ltr8.tson.json.reader;

import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.Optional;

/**
 * Where a type name ends up. [TSON-SCHEMA] §8.3 makes a reference a hop rather than a rewrite, so resolved
 * output states the chain the author wrote and a reader that wants the *shape* has to walk it.
 *
 * <p>One place, because three readers were walking it independently -- a field's stated value, a map's key
 * type, and now a choice variant's discrimination class -- and a chain walk that disagrees with itself about
 * where a name lands is a bug no test would name.
 */
final class JsonTypes {

    /** Linking has already refused a cycle, so this bounds a fault rather than a document. */
    private static final int MAX_HOPS = 64;

    private JsonTypes() {
    }

    /**
     * Where a chain ends: the name it ends at and that entry's definition.
     *
     * <p>The <b>name</b> is part of the answer and not a convenience. [TSON-SCHEMA] §4.2 dispatches the three
     * {@code unit} instances on the declaration's own name, their resolved bodies being identical, so a
     * caller asking the atom vocabulary for a parser needs the name the chain landed on rather than the one
     * it started from.
     */
    record Resolved(String name, TypeDefinition definition) {
    }

    /**
     * The entry {@code name} resolves to with every reference followed, or empty if the chain runs past
     * {@link #MAX_HOPS} or names something the schema does not declare -- neither of which a linked schema
     * should contain.
     */
    static Optional<Resolved> terminal(TsonSchema schema, String name) {
        String at = name;
        for (int hop = 0; hop < MAX_HOPS; hop++) {
            TypeDefinition definition = schema.entries().get(at);
            if (definition == null) {
                return Optional.empty();
            }
            if (!(definition.body() instanceof Reference reference)) {
                return Optional.of(new Resolved(at, definition));
            }
            at = reference.target().name();
        }
        return Optional.empty();
    }
}
