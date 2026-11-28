package io.ltr8.tson.json.reader;

import io.ltr8.tson.schema.ExternTarget;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.Alias;
import io.ltr8.tson.schema.meta.Top;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Where a type name ends up. [TSON-SCHEMA] §8.3 makes an alias a hop rather than a rewrite, so resolved
 * output states the chain the author wrote and a reader that wants the *shape* has to walk it.
 *
 * <p>One place, because three readers were walking it independently -- a field's stated value, a map's key
 * type, and a choice variant's discrimination class -- and a chain walk that disagrees with itself about
 * where a name lands is a bug no test would name.
 *
 * <p>Named for {@code tson-compiler}'s class of the same name, which does the same job over the same model:
 * the two stacks are peers, and a reader who knows one should recognise the other without being told.
 */
final class AliasChain {

    private AliasChain() {
    }

    /**
     * Where a chain ends: the name it ends at and that entry's definition.
     *
     * <p>The <b>name</b> is part of the answer and not a convenience: {@link #namesMeaning} indexes aliases by
     * the entry their chain lands on, which is a name and not a body.
     */
    record Resolved(String name, Top definition) {
    }

    /**
     * The inverse of {@link #terminal}: for each entry, every <em>written</em> name that means it -- the
     * aliases whose chain ends there. [TSON-SCHEMA] §7.2 compares "after alias flattening of
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
     * against, [TSON-SCHEMA] §7.2 comparing "after alias flattening of both".
     *
     * <p><b>The alias is kept rather than reduced to its target.</b> An alias entry compiles to its
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

    /**
     * Where a chain ends past externs as well as aliases: the entry {@code name} lands on and the linked schema whose
     * namespace holds it, the foreign one where the chain reaches an extern ([TSON-SCHEMA] §5.13). What a question
     * asked of the type's shape -- its class, a map key's form -- asks, the shape being the target's.
     */
    record Landed(TsonLinkedSchema in, String name, Top definition) {
    }

    /** {@code name}'s landing in {@code linked}, past its aliases and its extern, or empty where it reaches nothing. */
    static Optional<Landed> landed(TsonLinkedSchema linked, String name) {
        Optional<ExternTarget.Landing> landing = linked.landing(name);
        if (landing.isPresent()) {
            ExternTarget.Landing end = landing.get();
            return Optional.of(new Landed(end.in(), end.name(), end.definition()));
        }
        return terminal(linked.schema(), name).map(resolved -> new Landed(linked, resolved.name(),
                resolved.definition()));
    }

    /**
     * The entry {@code name} resolves to past its alias hops ({@link Alias#terminal}), or empty where the chain
     * closes on itself or names something the schema does not declare -- neither of which a linked schema contains.
     */
    static Optional<Resolved> terminal(TsonSchema schema, String name) {
        String at = Alias.terminal(name, schema.entries()::get);
        Top definition = schema.entries().get(at);
        return definition == null || definition instanceof Alias ? Optional.empty()
                : Optional.of(new Resolved(at, definition));
    }
}
