package io.ltr8.tson.json.reader;

import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Where a type name ends up. [TSON-SCHEMA] §8.3 makes a reference a hop rather than a rewrite, so resolved
 * output states the chain the author wrote and a reader that wants the *shape* has to walk it.
 *
 * <p>One place, because three readers were walking it independently -- a field's stated value, a map's key
 * type, and a choice variant's discrimination class -- and a chain walk that disagrees with itself about
 * where a name lands is a bug no test would name.
 *
 * <p>Named for {@code tson-compiler}'s class of the same name, which does the same job over the same model:
 * the two stacks are peers, and a reader who knows one should recognise the other without being told.
 */
final class ReferenceChain {

    /** Linking has already refused a cycle, so this bounds a fault rather than a document. */
    private static final int MAX_HOPS = 64;

    private ReferenceChain() {
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
    /**
     * The inverse of {@link #terminal}: for each entry, every <em>written</em> name that means it -- the
     * aliases whose chain ends there. [TSON-SCHEMA] §7.2 compares "after reference flattening of
     * <b>both</b>", so a reader matching a written {@code $type} against a set of entry names has to hold
     * the aliases too, or it refuses a name that denotes exactly what it admits.
     *
     * <p><b>Built once per compile</b>, because it is a property of the schema and not of the entry being
     * compiled: derived per reader it would walk every chain again for every entry, which is the schema's
     * size squared for an answer that does not change. An entry with no aliases is absent rather than
     * present-and-singleton -- the overwhelming majority -- and {@link #admitting} adds the name itself.
     */
    static Map<String, Set<String>> namesMeaning(TsonSchema schema) {
        Map<String, Set<String>> index = new LinkedHashMap<>();
        schema.entries().keySet().forEach(written -> terminal(schema, written).ifPresent(resolved -> {
            if (!resolved.name().equals(written)) {
                index.computeIfAbsent(resolved.name(), ignored -> new LinkedHashSet<>()).add(written);
            }
        }));
        return index;
    }

    /**
     * Each of {@code names} together with every alias that means it -- the set a written tag is matched
     * against. A materialised entry's own name is implementation-chosen (§8.2), so for a template
     * instantiation an alias is the only name an author has to write.
     *
     * <p><b>The alias is kept rather than reduced to its target.</b> A reference entry compiles to its
     * target's reader, so dispatching on the written name runs the same reader and reports under the name
     * the author typed.
     */
    static Set<String> admitting(Collection<String> names, Map<String, Set<String>> namesMeaning) {
        Set<String> admitted = new LinkedHashSet<>();
        for (String name : names) {
            admitted.add(name);
            admitted.addAll(namesMeaning.getOrDefault(name, Set.of()));
        }
        return admitted;
    }

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
