package io.ltr8.tson.json.reader;

import io.ltr8.tson.json.JsonSchemaLocation;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * The compilation environment beyond one entry: the schema being compiled, and how a composite reaches the
 * readers for its own children. Handed to every {@link ValueReaderFactory}.
 */
public record ValueReaderContext(TsonLinkedSchema linked, TypeReaderResolver readers,
                                 Map<String, Set<String>> namesMeaning) {

    /**
     * The alias index derived rather than supplied. A compile passes its own, built once: deriving it per
     * factory walks every reference chain again for every entry.
     */
    public ValueReaderContext(TsonLinkedSchema linked, TypeReaderResolver readers) {
        this(linked, readers, ReferenceChain.namesMeaning(linked.schema()));
    }

    public TsonSchema schema() {
        return linked.schema();
    }

    /** Each of {@code names} with every alias that means it -- what a written {@code $type} is matched against. */
    public Set<String> admitting(Collection<String> names) {
        return ReferenceChain.admitting(names, namesMeaning);
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
