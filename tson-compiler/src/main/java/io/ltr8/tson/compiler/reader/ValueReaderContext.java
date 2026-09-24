package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.ForeignSchemas;
import io.ltr8.tson.compiler.SchemaLocation;
import io.ltr8.tson.compiler.TsonTypeReaderResolver;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The compilation environment a {@link ValueReaderFactory} builds within, beyond its own entry's {@code
 * name}/{@code definition} (which stay direct {@code create} arguments): the whole {@link #linked} schema
 * being compiled -- the namespace of sibling entries a factory may need to reach a level up (a choice
 * classifies its variants for untagged recovery from it), plus where each of those entries was declared --
 * and the {@link #readers} a composite factory calls to resolve its child fields'/elements' own readers.
 *
 * <p>A context object rather than a widening parameter list: a factory that later needs a further handle
 * gains a field here instead of every factory's signature churning.
 *
 * <p>{@link #foreign} is the one handle that is not about the schema being compiled: [TSON-SCHEMA] §7.8's
 * scope push resolves a schema the <em>document</em> names, so {@link ScopedReader} is given where to go and
 * ask rather than an answer. Every other factory ignores it.
 */
public record ValueReaderContext(TsonLinkedSchema linked, TsonTypeReaderResolver readers, ForeignSchemas foreign,
                                 Map<String, Set<String>> namesMeaning, Map<String, List<String>> referrers) {

    /**
     * The index derived rather than supplied -- for a caller with no compilation around it. A compile passes
     * its own, built once: {@link Subsumption#namesMeaning} walks every entry's reference chain, so deriving
     * it per factory would be the schema's size squared for an answer that does not change.
     */
    public ValueReaderContext(TsonLinkedSchema linked, TsonTypeReaderResolver readers, ForeignSchemas foreign) {
        this(linked, readers, foreign, Subsumption.namesMeaning(linked.schema().entries()));
    }

    /** {@link #referrers} derived, for a caller that has its own {@code namesMeaning} but not this index. */
    public ValueReaderContext(TsonLinkedSchema linked, TsonTypeReaderResolver readers, ForeignSchemas foreign,
            Map<String, Set<String>> namesMeaning) {
        this(linked, readers, foreign, namesMeaning, referrers(linked.schema().entries()));
    }


    /** The resolved schema being compiled -- what a factory reaching a sibling entry wants. */
    public TsonSchema schema() {
        return linked.schema();
    }

    /**
     * The {@link SchemaLocation} the reader for entry {@code name} offers as its own declaration. The only
     * place one is built: the identity has to come from {@link TsonLinkedSchema#originOf} so that it and the
     * position are the same document's -- an imported entry's line belongs to the schema that declared it,
     * never to the one importing it.
     */
    public SchemaLocation locationOf(String name, TypeDefinition definition) {
        return SchemaLocation.of(linked.originOf(name), name, definition.position());
    }

    /**
     * Which entries name each target through a {@code REFERENCE} body, in declaration order.
     *
     * <p>The inverse of the alias hop, and the index {@link #bindingNamesFor} answers from. Built once per
     * compilation for the reason {@code namesMeaning} is: it is a property of the schema, not of the entry
     * being looked up.
     */
    public static Map<String, List<String>> referrers(Map<String, TypeDefinition> entries) {
        Map<String, List<String>> byTarget = new LinkedHashMap<>();
        entries.forEach((name, definition) -> {
            if (definition.body() instanceof Reference reference) {
                byTarget.computeIfAbsent(reference.target().name(), target -> new ArrayList<>()).add(name);
            }
        });
        Map<String, List<String>> fixed = new LinkedHashMap<>();
        byTarget.forEach((target, names) -> fixed.put(target, List.copyOf(names)));
        return Map.copyOf(fixed);
    }

    /** {@link #bindingNamesFor(String, TypeDefinition)} for a name whose definition this looks up itself. */
    public List<String> bindingNamesFor(String name) {
        TypeDefinition definition = schema().entries().get(name);
        return definition == null ? List.of(name) : bindingNamesFor(name, definition);
    }

    /**
     * The names a bind lookup for entry {@code name} tries, in order: the names an author wrote for it, then
     * the entry's own.
     *
     * <p><b>A binding map is written in the names the author wrote.</b> An entry a template application
     * materialised is named by content ([TSON-SCHEMA] §8.2 -- resolver-chosen and unreachable from source),
     * so {@code ping => msg_of<"ping", ping_body>} reaches a bind context under {@code ping}: the hash is not
     * knowable when the map is written, and a generator emitting one cannot invent it.
     *
     * <p><b>Only a derived entry is reached this way</b>, which is the same test {@code EntryDisplayName}
     * applies: an entry with a source position was declared, so its own name is the one the author wrote and
     * an alias naming it must never redirect its binding.
     */
    public List<String> bindingNamesFor(String name, TypeDefinition definition) {
        List<String> written = definition.position().isPresent()
                ? List.of() : referrers.getOrDefault(name, List.of());
        if (written.isEmpty()) {
            return List.of(name);
        }
        List<String> candidates = new ArrayList<>(written);
        candidates.add(name);
        return List.copyOf(candidates);
    }
}
