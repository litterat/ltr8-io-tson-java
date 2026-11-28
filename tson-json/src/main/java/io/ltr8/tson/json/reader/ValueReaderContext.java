package io.ltr8.tson.json.reader;

import io.ltr8.tson.encoding.TsonTypeBinding;
import io.ltr8.tson.encoding.plan.EntryBindings;
import io.ltr8.tson.json.JsonSchemaLocation;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeChain;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The compilation environment beyond one entry: the schema being compiled and how a composite reaches the readers
 * for its own children. Handed to every {@link ValueReaderFactory}.
 */
public record ValueReaderContext(TsonLinkedSchema linked, TypeReaderResolver readers,
                                 Map<String, Set<String>> namesMeaning, EntryBindings bindings) {

    /**
     * The alias index derived rather than supplied, and {@code bindings} those a bind compile loaded, null for tree
     * mode. A compile passes its own index, built once: deriving it per factory walks every alias chain again for
     * every entry.
     */
    public ValueReaderContext(TsonLinkedSchema linked, TypeReaderResolver readers, EntryBindings bindings) {
        this(linked, readers, AliasChain.namesMeaning(linked.schema()), bindings);
    }

    /** A context a tree-mode compile builds within, which binds nothing. */
    public ValueReaderContext(TsonLinkedSchema linked, TypeReaderResolver readers) {
        this(linked, readers, null);
    }

    /**
     * The binding of entry {@code name}, from the bindings this compile loaded: the one answer every bind-mode
     * factory builds from.
     *
     * @throws io.ltr8.tson.base.MissingBindingException where nothing binds it
     */
    public TsonTypeBinding bound(String name) {
        return loaded(name).require(linked.entryKey(name).orElseThrow(() -> new IllegalStateException("'" + name
                + "' reaches no entry of " + linked.identity())));
    }

    /** The binding of entry {@code name}; empty where nothing binds it, or {@code name} is no entry. */
    public Optional<TsonTypeBinding> bindingOf(String name) {
        return loaded(name).get(name);
    }

    private EntryBindings loaded(String name) {
        if (bindings == null) {
            throw new IllegalStateException("a bind-mode reader asked what '" + name + "' binds to, in a compile "
                    + "that loaded no binding");
        }
        return bindings;
    }

    public TsonSchema schema() {
        return linked.schema();
    }

    /**
     * Each of {@code names} with every alias that means it -- what a written {@code $type} is matched against. A name
     * no entry holds is the form of a type written in place, which no tag names ([TSON-SCHEMA] §7.2), and admits
     * nothing.
     */
    public Set<String> admitting(Collection<String> names) {
        return AliasChain.admitting(names.stream().filter(schema().entries()::containsKey).toList(), namesMeaning);
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
     * The entries IS-A {@code name}, whose value is {@code definition}: its chain's {@code subtypes}, each a type, a
     * parametric record being in no index.
     */
    public List<String> subtypesOf(String name, Top definition) {
        return TypeChain.subtypesOf(definition);
    }

    /**
     * The entries IS-A {@code name} that have instances of their own -- {@link #subtypesOf}, less every abstract
     * record, which a value can never be directly. What a refusal at an abstract position names as satisfying it:
     * naming an abstract subtype would only refuse in turn.
     */
    public List<String> instantiableSubtypesOf(String name, Top definition) {
        return subtypesOf(name, definition).stream()
                .filter(subtype -> {
                    io.ltr8.tson.schema.meta.Top entry = schema().entries().get(subtype);
                    return switch (entry) {
                        case io.ltr8.tson.schema.meta.RecordBody record ->
                                record.extension() != io.ltr8.tson.schema.meta.RecordExtensionType.ABSTRACT;
                        case null -> false;
                        default -> true;
                    };
                })
                .toList();
    }
}
