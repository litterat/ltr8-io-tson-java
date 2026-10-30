package io.ltr8.tson.json.reader;

import io.ltr8.tson.json.JsonSchemaLocation;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeChain;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The compilation environment beyond one entry: the schema being compiled and how a composite reaches the readers
 * for its own children. Handed to every {@link ValueReaderFactory}.
 */
public record ValueReaderContext(TsonLinkedSchema linked, TypeReaderResolver readers,
                                 Map<String, Set<String>> namesMeaning) {

    /**
     * The alias index derived rather than supplied. A compile passes its own, built once: deriving it per
     * factory walks every alias chain again for every entry.
     */
    public ValueReaderContext(TsonLinkedSchema linked, TypeReaderResolver readers) {
        this(linked, readers, AliasChain.namesMeaning(linked.schema()));
    }

    public TsonSchema schema() {
        return linked.schema();
    }

    /** Each of {@code names} with every alias that means it -- what a written {@code $type} is matched against. */
    public Set<String> admitting(Collection<String> names) {
        return AliasChain.admitting(names, namesMeaning);
    }

    /**
     * Where {@code name}'s declaration sits, for a reader to offer as it begins reading. The identity comes
     * from {@code entryOrigins}, so a declaration flattened in by an {@code !!import} carries the schema that
     * actually declares it rather than the one that imported it.
     */
    public JsonSchemaLocation locationOf(String name) {
        return JsonSchemaLocation.of(linked.originOf(name), name, schema().positionOf(name));
    }

    /**
     * The entries IS-A {@code name}, whose value is {@code definition}: its chain's {@code subtypes} -- a type's,
     * and a family base template's, whose subtypes are its members.
     */
    public List<String> subtypesOf(String name, Top definition) {
        return TypeChain.subtypesOf(definition);
    }

    /**
     * The entries IS-A {@code name} that have instances of their own -- {@link #subtypesOf}, less every abstract
     * record and every family-base template, neither of which a value can be directly. What a refusal at an
     * abstract position names as satisfying it: naming an abstract subtype would only refuse in turn.
     */
    public List<String> instantiableSubtypesOf(String name, Top definition) {
        return subtypesOf(name, definition).stream()
                .filter(subtype -> {
                    io.ltr8.tson.schema.meta.Top entry = schema().entries().get(subtype);
                    if (entry == null || io.ltr8.tson.schema.meta.OpenEntry.isOpen(entry)) {
                        return false;
                    }
                    return !(entry instanceof io.ltr8.tson.schema.meta.RecordBody record)
                            || record.extension() != io.ltr8.tson.schema.meta.RecordExtensionType.ABSTRACT;
                })
                .toList();
    }
}
