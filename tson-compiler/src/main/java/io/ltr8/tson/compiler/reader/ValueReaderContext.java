package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.SchemaLocation;
import io.ltr8.tson.compiler.TsonTypeReaderResolver;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.EntryDisplayName;
import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.Alias;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeChain;

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
 */
public record ValueReaderContext(TsonLinkedSchema linked, TsonTypeReaderResolver readers,
                                 Map<String, Set<String>> namesMeaning, Map<String, List<String>> referrers) {

    /**
     * The index derived rather than supplied -- for a caller with no compilation around it. A compile passes
     * its own, built once: {@link Subsumption#namesMeaning} walks every entry's alias chain, so deriving
     * it per factory would be the schema's size squared for an answer that does not change.
     */
    public ValueReaderContext(TsonLinkedSchema linked, TsonTypeReaderResolver readers) {
        this(linked, readers, Subsumption.namesMeaning(linked.schema().entries()));
    }

    /** {@link #referrers} derived, for a caller that has its own {@code namesMeaning} but not this index. */
    public ValueReaderContext(TsonLinkedSchema linked, TsonTypeReaderResolver readers,
            Map<String, Set<String>> namesMeaning) {
        this(linked, readers, namesMeaning, referrers(linked.schema().entries()));
    }


    /** The resolved schema being compiled -- what a factory reaching a sibling entry wants. */
    public TsonSchema schema() {
        return linked.schema();
    }

    /**
     * The namespace a bind lookup asks entry {@code name} within: the canonical identity of the schema declaring it,
     * so one entry reached through several schemas binds once, and two schemas' entries of one name bind apart.
     */
    public String namespaceOf(String name) {
        return linked.declaringIdentity(name);
    }

    /**
     * The {@link SchemaLocation} the reader for entry {@code name} offers as its own declaration. The only
     * place one is built: the identity has to come from {@link TsonLinkedSchema#originOf} so that it and the
     * position are the same document's -- an imported entry's line belongs to the schema that declared it,
     * never to the one importing it.
     */
    public SchemaLocation locationOf(String name) {
        return SchemaLocation.of(linked.originOf(name), name, schema().positionOf(name));
    }

    /**
     * The tag a value read by entry {@code name}'s reader carries in a tree: the entry's name, or none for a type
     * written in place, which has no entry and no name a document could write as a tag.
     */
    public java.util.Optional<String> tagOf(String name) {
        return schema().entries().containsKey(name) ? java.util.Optional.of(name) : java.util.Optional.empty();
    }

    /**
     * What a message calls entry {@code name}, whose value is {@code definition}: its own name where an author
     * wrote it, and otherwise the form that produced it ({@link EntryDisplayName}). A type written in place has no
     * entry, and its reader's name is already its form as written, its constructor's application included ({@link
     * io.ltr8.tson.schema.meta.TypeInline#text()}), which its body alone could not say.
     */
    public String displayName(String name, Top definition) {
        if (!schema().entries().containsKey(name)) {
            return name;
        }
        return EntryDisplayName.of(name, definition, schema().factsOf(name), schema().applicationOf(name));
    }

    /**
     * What the author wrote that names entry {@code name} ({@link EntryDisplayName#authored}): its own name, or for
     * an entry with no source position the form or the application its key records. A type written in place is
     * named by its form, as {@link #displayName} names it.
     */
    public String authoredName(String name, Top definition) {
        if (!schema().entries().containsKey(name)) {
            return name;
        }
        return EntryDisplayName.authored(name, definition, schema().factsOf(name), schema().applicationOf(name))
                .orElse(null);
    }

    /**
     * The entries IS-A {@code name}, whose value is {@code definition}: its chain's {@code subtypes} ({@link
     * TypeChain#subtypesOf}), each a type, a parametric record being in no index.
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

    /**
     * Which entries name each target through an alias body, in declaration order.
     *
     * <p>The inverse of the alias hop, and the index {@link #bindingNamesFor} answers from. Built once per
     * compilation for the reason {@code namesMeaning} is: it is a property of the schema, not of the entry
     * being looked up.
     */
    public static Map<String, List<String>> referrers(Namespace<? extends Top> entries) {
        Map<String, List<String>> byTarget = new LinkedHashMap<>();
        entries.forEach((name, definition) -> {
            if (definition instanceof Alias alias) {
                byTarget.computeIfAbsent(alias.target().name(), target -> new ArrayList<>()).add(name);
            }
        });
        Map<String, List<String>> fixed = new LinkedHashMap<>();
        byTarget.forEach((target, names) -> fixed.put(target, List.copyOf(names)));
        return Map.copyOf(fixed);
    }

    /**
     * The names a bind lookup for entry {@code name} tries, in order: the names an author wrote for it, then the
     * entry's own, then, for a constructor composed over another with no field added, its parent's ({@link
     * TsonLinkedSchema#composedOver}).
     *
     * <p><b>A binding map is written in the names the author wrote.</b> Only an entry with no source position --
     * one the kernel's bootstrap builds, or one read from a resolved form -- is also looked up under the names
     * that reach it through an alias, which is the same test {@code EntryDisplayName} applies: an entry with a
     * source position was declared, so its own name is the one the author wrote and an alias naming it must never
     * redirect its binding.
     */
    public List<String> bindingNamesFor(String name) {
        if (schema().entries().get(name) == null) {
            return List.of(name);
        }
        List<String> written = schema().positionOf(name).isPresent()
                ? List.of() : referrers.getOrDefault(name, List.of());
        List<String> candidates = new ArrayList<>(written);
        candidates.add(name);
        linked.composedOver(name).ifPresent(parent -> candidates.addAll(bindingNamesFor(parent)));
        return List.copyOf(candidates);
    }
}
