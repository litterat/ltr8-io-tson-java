package io.ltr8.tson.json.reader;

import io.ltr8.tson.json.JsonSchemaLocation;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.TypeDefinition;

/**
 * The compilation environment beyond one entry: the schema being compiled, and how a composite reaches the
 * readers for its own children. Handed to every {@link ValueReaderFactory}.
 */
public record ValueReaderContext(TsonLinkedSchema linked, TypeReaderResolver readers) {

    public TsonSchema schema() {
        return linked.schema();
    }

    /**
     * Where {@code name}'s declaration sits, for a reader to offer as it begins reading. The identity comes
     * from {@code entryOrigins}, so a declaration flattened in by an {@code !!import} carries the schema that
     * actually declares it rather than the one that imported it.
     */
    public JsonSchemaLocation locationOf(String name, TypeDefinition definition) {
        return JsonSchemaLocation.of(linked.originOf(name), name, definition.position());
    }
}
