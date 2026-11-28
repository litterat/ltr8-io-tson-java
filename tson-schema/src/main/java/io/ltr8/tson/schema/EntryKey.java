package io.ltr8.tson.schema;

import java.util.Objects;

/**
 * An entry's identity, which is what a binder is asked: the canonical identity of the schema declaring it
 * ([TSON-DATA] §2.2.1) and its name there ([TSON-SCHEMA] §8.3), never the scope it was reached through.
 */
public record EntryKey(String declaringSchema, String name) {

    public EntryKey {
        Objects.requireNonNull(declaringSchema, "declaringSchema");
        Objects.requireNonNull(name, "name");
    }

    @Override
    public String toString() {
        return declaringSchema + "#" + name;
    }
}
