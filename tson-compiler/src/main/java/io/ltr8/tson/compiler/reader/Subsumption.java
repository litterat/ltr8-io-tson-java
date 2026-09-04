package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.resolver.ReferenceChain;
import io.ltr8.tson.compiler.TsonTypeReaderResolver;
import io.ltr8.tson.schema.meta.Atom;
import io.ltr8.tson.schema.meta.Product;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * [TSON-SCHEMA] §7.2's rule that a value's own type annotation must be admitted by the position it stands in:
 * "at a position whose declared type is {@code T}, a value annotated {@code !S} is valid if and only if
 * ... {@code S} is {@code T} or {@code T} appears in {@code S}'s transitive {@code type_definition.supertypes}".
 *
 * <p>{@link VariantSchemaReader} already decides exactly that -- no type-ref or the entry's own name reads
 * through the entry's reader, a subtype dispatches to the subtype's, and anything else is {@code
 * UNKNOWN_TYPE_REF} -- and it is generic over the reader it wraps. What this adds is reaching every position
 * the rule covers rather than the one that happened to be wired: it was applied only where a record had a
 * non-empty {@code subtypes()}, so a stray or wrong annotation was silently discarded at every atom, array,
 * map, tuple, and at every record whose type had no subtype. The rule is unconditional; the enforcement was
 * not.
 *
 * <p><b>The guard follows the body, not the declared kind</b>, and only {@code Atom} and {@code Product}
 * bodies take it. §7.2 excludes the others by name: a choice discriminates "by variant membership (§5.4)"
 * and an {@code extern} "by the foreign schema's namespace (§7.8)", each its own relation with its own
 * dispatcher, and subsumption would refuse the very variant tags those positions exist to take. A
 * {@code Reference} body takes no guard either, and needs none: a reference entry compiles to its target's
 * reader, which carries the guard, and the alias is among the names that reader admits. Reading the body
 * rather than {@code kind()} is deliberate: a hand-built entry can carry a {@code ChoiceBody} under
 * {@code PRODUCT}, and what decides how a value is read is the body.
 */
public final class Subsumption {

    private Subsumption() {
    }

    /**
     * {@code reader} guarded by §7.2, or {@code reader} unchanged where the rule does not apply or something
     * already applies it -- a record with subtypes arrives already wrapped by its own factory, and wrapping
     * twice would report the same refusal from two places.
     */
    public static TsonTypeReader<?> guard(String name, TypeDefinition definition, TsonTypeReader<?> reader,
                                          Map<String, Set<String>> namesMeaning, TsonTypeReaderResolver resolver) {
        if (!(definition.body() instanceof Atom || definition.body() instanceof Product)) {
            return reader;
        }
        if (reader instanceof VariantSchemaReader || reader instanceof VariantBindReader) {
            return reader;
        }
        return new VariantSchemaReader(name, selfNames(name, namesMeaning), reader, definition.subtypes(),
                resolver);
    }

    /**
     * For each entry, the written names that mean it: the chain-end of every name in {@code entries},
     * grouped. **Built once per compile**, because it is a property of the schema and not of the entry being
     * guarded -- {@code selfNames} used to answer the same question by scanning every entry again for every
     * entry compiled, which is the schema's size squared for a fact that does not change between calls.
     *
     * <p>An entry with no aliases is absent rather than present-and-singleton: the overwhelming majority,
     * and {@link #selfNames} adds the name itself anyway.
     */
    public static Map<String, Set<String>> namesMeaning(Map<String, TypeDefinition> entries) {
        Map<String, Set<String>> index = new LinkedHashMap<>();
        entries.forEach((written, definition) -> {
            String terminal = ReferenceChain.terminal(written, entries);
            if (!terminal.equals(written)) {
                index.computeIfAbsent(terminal, ignored -> new LinkedHashSet<>()).add(written);
            }
        });
        return index;
    }

    /**
     * The written names that mean {@code name}: itself, plus every {@code REFERENCE} entry whose chain ends
     * at it. §7.2 compares "after reference flattening of <b>both</b>", and an alias and its target are one
     * type -- so {@code !created} at a {@code created}-typed position names the position's own type even
     * though the reader running there belongs to the instantiation {@code created} aliases. Fixed at compile
     * time, because the reader cannot know which of its aliases a given position was written as.
     */
    private static Set<String> selfNames(String name, Map<String, Set<String>> namesMeaning) {
        Set<String> aliases = namesMeaning.get(name);
        if (aliases == null) {
            return Set.of(name);
        }
        Set<String> names = new LinkedHashSet<>();
        names.add(name);
        names.addAll(aliases);
        return names;
    }
}
