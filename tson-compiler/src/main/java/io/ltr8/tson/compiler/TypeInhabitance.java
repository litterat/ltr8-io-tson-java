package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.resolver.AliasChain;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.Atom;
import io.ltr8.tson.schema.meta.ValueEntry;
import io.ltr8.tson.schema.meta.DeclaredType;
import io.ltr8.tson.schema.meta.Sum;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.FieldGroup;
import io.ltr8.tson.schema.meta.ReferenceBody;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.OpenEntry;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordExtensionType;

import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Alias;
import io.ltr8.tson.schema.meta.TupleElement;

import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeExpr;
import io.ltr8.tson.schema.meta.TypeInline;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which entries some finite document can satisfy (Part 2 §5.9, §5.10.1's productivity rule). A type
 * can be perfectly well-formed -- every reference resolving, every constraint coherent -- and still have no
 * value at all, because its recursion never reaches a base case:
 *
 * <pre>
 *   x    =&gt; { y: y }                                      an x needs a y needs an x
 *   y    =&gt; { x: x }
 *   tree =&gt; &lt;T&gt; { value: T  children: [tree&lt;T&gt;; 1..] }    every node needs a child
 * </pre>
 *
 * <p>Left unchecked the mistake surfaces at the first document, as {@code missing required field 'x'} --
 * blaming the data for a defect in the schema, at a line the author of the data does not control.
 *
 * <p><b>A least fixed point over the entry graph, not a search.</b> Every entry starts unknown, and a round
 * marks each one whose body is satisfied by what is already marked; rounds repeat until nothing changes. At
 * most one round per entry can add anything, so it terminates in O(entries) rounds. The question is decidable
 * because the graph is finite: this is not the general "does this type have a value" problem, only "does this
 * recursion reach a base case".
 *
 * <p><b>Exact, total and two-valued</b> -- unlike {@link ChoiceDisjointness}, which had to give up exactness
 * to stay total, this needs no such trade. There is no third answer to report.
 *
 * <p><b>Scope is structural.</b> An atom whose own facets admit nothing ({@code int8 ^ { min: 300 }}) is
 * uninhabited too, but that is a question for the family that owns those facets, and the resolver asks it
 * ({@code Atom.coherenceCheck}). Every atom body counts as inhabited here.
 */
final class TypeInhabitance {

    private TypeInhabitance() {
    }

    /**
     * The names some finite document can satisfy. Everything else in {@code namespace} is uninhabited --
     * {@link #cycleThrough} explains one such entry to its author.
     */
    static Set<String> derive(Map<String, Top> namespace) {
        Set<String> inhabited = new HashSet<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<String, Top> entry : namespace.entrySet()) {
                if (!inhabited.contains(entry.getKey()) && isInhabited(entry.getValue(), namespace, inhabited)) {
                    inhabited.add(entry.getKey());
                    changed = true;
                }
            }
        }
        return inhabited;
    }

    /**
     * Whether one definition is satisfied by the entries marked so far.
     *
     * <p><b>An open entry counts as inhabited, unjudged.</b> A template is not a type and no document ever
     * has one; its closures are judged where they stand, a declared application as an entry in this map and one
     * closed in place at the slot holding it. A template nobody applies is judged nowhere -- §5.10: "an unapplied
     * template is checked no further and receives no verdict".
     */
    private static boolean isInhabited(Top definition, Map<String, Top> namespace,
            Set<String> inhabited) {
        // An open record is not walked: its parameters mean nothing until substitution supplies the arguments,
        // so a template is inhabited here and each closure, which does supply them, is judged on its own.
        if (OpenEntry.isOpen(definition)) {
            return true;
        }
        return switch (definition) {
            case RecordBody record -> recordInhabited(record, namespace, inhabited);
            case ArrayBody array -> array.voidable()
                    || isEmptyAllowed(array.minItems())
                    || slotInhabited(array.elementType(), namespace, inhabited);
            case MapBody map -> isEmptyAllowed(map.minItems())
                    || (refInhabited(map.keyType(), namespace, inhabited)
                            && slotInhabited(map.valueType(), namespace, inhabited));
            case io.ltr8.tson.schema.meta.NamespaceBody ignored -> true; // the empty map is a value of it
            case io.ltr8.tson.schema.meta.TupleBody tuple -> tuple.elements().stream()
                    .allMatch(element -> positionInhabited(element, namespace, inhabited));
            // A sum needs one good variant, where a product needs all its parts -- the one place the walk
            // branches rather than conjoins, and the reason `(leaf | node)` survives a non-productive `node`.
            case ChoiceBody choice -> choice.variants().stream()
                    .anyMatch(variant -> slotInhabited(variant, namespace, inhabited));
            // A reference that names types admits the names of its members, whatever the types they name admit,
            // so it is inhabited exactly when linking found a member. One that names values admits a name whose
            // referent only the linker judges.
            case ReferenceBody reference -> !reference.namesTypes() || !reference.memberNames().isEmpty();
            case Alias alias -> refInhabited(alias.target(), namespace, inhabited);

            case Atom ignored -> true; // an atom's own satisfiability is its family's question, not this one
            case DeclaredType ignored -> true; // its values name their own types
            case io.ltr8.tson.schema.meta.DependentBody ignored -> true; // as a declared type, chosen by its sibling
            case ValueEntry ignored -> true; // a value, which nothing is typed by
        };
    }

    /**
     * A record needs every part it cannot do without, and one option it can choose in every group it must
     * choose from.
     *
     * <p><b>The groups are walked separately because their members hide from the field walk</b>: §5.11 makes a
     * group's members uniformly optional in {@code fields}, with the requirement carried by the group's own
     * {@code optional}. Reading only the field list would find nothing required and call every group satisfied.
     */
    private static boolean recordInhabited(RecordBody record, Map<String, Top> namespace,
            Set<String> inhabited) {
        Set<String> grouped = new HashSet<>();
        record.groups().forEach(group -> grouped.addAll(group.memberNames()));
        for (Map.Entry<String, RecordField> entry : record.fields().entrySet()) {
            RecordField field = entry.getValue();
            if (grouped.contains(entry.getKey()) || demandsNothing(field)) {
                continue;
            }
            if (!satisfiable(field, namespace, inhabited)) {
                return false;
            }
        }
        for (FieldGroup group : record.groups()) {
            if (!group.optional() && group.members().stream()
                    .noneMatch(option -> choosable(option, group, record, namespace, inhabited))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether some document can choose this option ([TSON-SCHEMA] §5.11): it states every member the group does
     * not mark {@code ?}, and at least one member, so an option whose members are all marked needs one of them.
     */
    private static boolean choosable(List<String> option, FieldGroup group, RecordBody record,
            Map<String, Top> namespace, Set<String> inhabited) {
        boolean anyStated = false;
        for (String member : option) {
            boolean stated = memberSatisfiable(member, record, namespace, inhabited);
            if (!stated && !group.optionalMembers().contains(member)) {
                return false;
            }
            anyStated |= stated;
        }
        return anyStated;
    }

    /** The member's field can be stated; a member naming no field is the linker's to report, and counts as one. */
    private static boolean memberSatisfiable(String member, RecordBody record, Map<String, Top> namespace,
            Set<String> inhabited) {
        RecordField field = record.fields().get(member);
        return field == null || satisfiable(field, namespace, inhabited);
    }

    /**
     * A field that admits {@code _}, or that a document may leave out and that supplies no value of its own,
     * places no demand on its type -- so either guards a recursive reference. Every other field does, one
     * carrying a value included: a fixed or default value of a type nothing can satisfy does not exist either.
     */
    private static boolean demandsNothing(RecordField field) {
        return field.voidable() || (field.optional() && field.value().isEmpty());
    }

    /**
     * Whether some document can state this field: as {@code _} where it is voidable, else with a value of its
     * type. {@code void}'s only value is {@code _}, so a {@code void} field that is not voidable can be stated
     * by nothing -- {@code a: void} empties its record, where {@code a?: void} empties only the field.
     */
    private static boolean satisfiable(RecordField field, Map<String, Top> namespace,
            Set<String> inhabited) {
        if (field.voidable() || field.sibling().isPresent()) {
            // A dependent field is stated with a value of whichever type its sibling names (§5.2).
            return true;
        }
        return switch (field.typeExpr()) {
            case TypeRef reference -> !AliasChain.resolvesToVoid(reference.name(), namespace)
                    && refInhabited(reference, namespace, inhabited);
            case TypeInline inline -> inlineInhabited(inline, namespace, inhabited);
        };
    }

    /**
     * A type written in place, inhabited by the rules its body would be judged by as an entry, but for an abstract
     * record in place, which is empty ([TSON-SCHEMA] §4.2): it has no direct instances, and with no name no schema can
     * declare a member of it, where a declared abstract record stays open to the members an importing schema adds.
     * {@code discriminators} alone decide nothing: a record in place stating them and not abstract is read as any
     * record is.
     */
    private static boolean inlineInhabited(TypeInline inline, Map<String, Top> namespace, Set<String> inhabited) {
        return !abstractInPlace(inline) && isInhabited(inline.of(), namespace, inhabited);
    }

    private static boolean abstractInPlace(TypeInline inline) {
        return inline.of() instanceof RecordBody record && record.extension() == RecordExtensionType.ABSTRACT;
    }

    private static boolean positionInhabited(TupleElement element, Map<String, Top> namespace,
            Set<String> inhabited) {
        return element.voidable()
                || slotInhabited(element.elementType(), namespace, inhabited);
    }

    /**
     * What a type slot holds, inhabited: a name as {@link #refInhabited} judges it, and a type written in place by
     * the same rules its body would be judged by as an entry, the names inside it judged in turn.
     */
    private static boolean slotInhabited(TypeExpr type, Map<String, Top> namespace, Set<String> inhabited) {
        return switch (type) {
            case TypeRef reference -> refInhabited(reference, namespace, inhabited);
            case TypeInline inline -> inlineInhabited(inline, namespace, inhabited);
        };
    }

    /**
     * The next link of the chain through a slot nothing satisfies: its name, or, for a type written in place, the
     * first name inside it that is not satisfied -- none for an abstract record in place, empty by itself.
     */
    private static String slotDependency(TypeExpr type, Map<String, Top> namespace, Set<String> inhabited) {
        return switch (type) {
            case TypeRef reference -> reference.name();
            case TypeInline inline -> abstractInPlace(inline)
                    ? null : firstUnsatisfiedDependency(inline.of(), namespace, inhabited);
        };
    }

    /**
     * Whether a container may be empty -- the guard that makes recursion through one terminate. {@code [tree]}
     * is satisfied by {@code []} whatever {@code tree} turns out to be; {@code [tree; 1..]} is satisfied only
     * if {@code tree} is. That single distinction is what separates ordinary recursion from the runaway kind.
     */
    private static boolean isEmptyAllowed(Optional<Long> minItems) {
        return minItems.isEmpty() || minItems.get() <= 0;
    }

    /**
     * A name this namespace does not hold counts as inhabited. The reference is unresolved, which {@code
     * TsonSchemaLinker.validateEntry} has already reported against this very entry -- calling it uninhabited
     * too would report the same defect twice, in words that name a different problem.
     */
    private static boolean refInhabited(TypeRef ref, Map<String, Top> namespace,
            Set<String> inhabited) {
        return !namespace.containsKey(ref.name()) || inhabited.contains(ref.name());
    }

    /**
     * The chain from an uninhabited entry back to itself, or to whatever else it depends on, for the
     * diagnostic. Walks the demands the rules above make -- the parts a value cannot do without -- so the
     * chain shown is the one that actually has to be broken.
     */
    static List<String> cycleThrough(String name, Map<String, Top> namespace,
            Set<String> inhabited) {
        List<String> chain = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String current = name;
        while (true) {
            chain.add(current);
            // The repeat is written down rather than dropped: `x needs y needs x` is the cycle, and `x needs
            // y` is only half of an explanation.
            if (!seen.add(current)) {
                break;
            }
            String next = firstUnsatisfiedDependency(namespace.get(current), namespace, inhabited);
            if (next == null) {
                break;
            }
            current = next;
        }
        return List.copyOf(chain);
    }

    /**
     * The part of a record nothing satisfies: a required field, or -- when every one of those is fine -- the
     * first unsatisfiable member of a group that has to be chosen from and has no option to choose. Following
     * the group matters because its members are optional in {@code fields}, so a chain that walked only
     * required fields would stop at the record and explain nothing.
     */
    private static String recordDependency(RecordBody record, Map<String, Top> namespace,
            Set<String> inhabited) {
        Set<String> grouped = new HashSet<>();
        record.groups().forEach(group -> grouped.addAll(group.memberNames()));
        for (Map.Entry<String, RecordField> entry : record.fields().entrySet()) {
            RecordField field = entry.getValue();
            if (!grouped.contains(entry.getKey()) && !demandsNothing(field)
                    && !satisfiable(field, namespace, inhabited)) {
                return slotDependency(field.typeExpr(), namespace, inhabited);
            }
        }
        return record.groups().stream()
                .filter(group -> !group.optional() && group.members().stream()
                        .noneMatch(option -> choosable(option, group, record, namespace, inhabited)))
                .flatMap(group -> group.memberNames().stream())
                .flatMap(member -> java.util.Optional.ofNullable(record.fields().get(member)).stream())
                .filter(field -> !satisfiable(field, namespace, inhabited))
                .map(field -> slotDependency(field.typeExpr(), namespace, inhabited))
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    /** The first thing a definition demands that nothing satisfies -- the next link of the chain. */
    private static String firstUnsatisfiedDependency(Top definition,
            Map<String, Top> namespace, Set<String> inhabited) {
        if (definition == null || OpenEntry.isOpen(definition)) {
            return null; // an open entry is inhabited, as isInhabited says
        }
        return switch (definition) {
            case RecordBody record -> recordDependency(record, namespace, inhabited);
            case ArrayBody array -> slotInhabited(array.elementType(), namespace, inhabited)
                    ? null : slotDependency(array.elementType(), namespace, inhabited);
            case MapBody map -> slotInhabited(map.valueType(), namespace, inhabited)
                    ? null : slotDependency(map.valueType(), namespace, inhabited);
            case io.ltr8.tson.schema.meta.NamespaceBody ignored -> null;
            case io.ltr8.tson.schema.meta.TupleBody tuple -> tuple.elements().stream()
                    .filter(element -> !positionInhabited(element, namespace, inhabited))
                    .map(element -> slotDependency(element.elementType(), namespace, inhabited))
                    .filter(java.util.Objects::nonNull).findFirst().orElse(null);
            case Alias alias -> refInhabited(alias.target(), namespace, inhabited)
                    ? null : alias.target().name();
            // A choice fails on every variant at once, so no single link continues the chain; the rest name none.
            case Sum ignored -> null;
            case Atom ignored -> null;

            case ValueEntry ignored -> null;
        };
    }
}
