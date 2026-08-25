package io.ltr8.tson.compiler;

import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.ElementState;
import io.ltr8.tson.schema.meta.FieldGroup;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.TemplateArgument;
import io.ltr8.tson.schema.meta.TupleElement;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeRef;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which entries some finite document can satisfy (Part 2 §3.4.1, §5.9, §5.10.1's productivity rule). A type
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
 * uninhabited too, but that is a question for the constraint family that owns those facets, next to {@code
 * AtomNarrowing} -- see {@code BACKLOG.md}. Every atom body counts as inhabited here.
 */
final class TypeInhabitance {

    private TypeInhabitance() {
    }

    /**
     * The names some finite document can satisfy. Everything else in {@code namespace} is uninhabited --
     * {@link #cycleThrough} explains one such entry to its author.
     */
    static Set<String> derive(Map<String, TypeDefinition> namespace) {
        Set<String> inhabited = new HashSet<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<String, TypeDefinition> entry : namespace.entrySet()) {
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
     * <p><b>An open entry is judged too, with its parameters assumed inhabited.</b> A template is not a type
     * and no document ever has one, so the question could have been left to its closures -- but then a
     * template nobody applies would ship broken, which is the failure {@code TemplateRegularity} exists to
     * prevent for the neighbouring rule. Assuming the parameters inhabited makes the verdict sound in the
     * direction that matters: if the body cannot be satisfied even when every argument can, then no
     * application of it can be either. A parameter is not an entry, so {@link #refInhabited} already treats
     * one as inhabited, and an application {@code tree<p0>} already depends on {@code tree} -- both fall out
     * of looking the reference's own name up.
     */
    private static boolean isInhabited(TypeDefinition definition, Map<String, TypeDefinition> namespace,
            Set<String> inhabited) {
        return switch (definition.body()) {
            case RecordBody record -> recordInhabited(record, namespace, inhabited);
            case ArrayBody array -> array.state() == ElementState.OPTIONAL
                    || isEmptyAllowed(array.minItems())
                    || refInhabited(array.elementType(), namespace, inhabited);
            case MapBody map -> isEmptyAllowed(map.minItems())
                    || (refInhabited(map.keyType(), namespace, inhabited)
                            && refInhabited(map.valueType(), namespace, inhabited));
            case io.ltr8.tson.schema.meta.TupleBody tuple -> tuple.elements().stream()
                    .allMatch(element -> positionInhabited(element, namespace, inhabited));
            // A sum needs one good variant, where a product needs all its parts -- the one place the walk
            // branches rather than conjoins, and the reason `(leaf | node)` survives a non-productive `node`.
            case ChoiceBody choice -> choice.variants().stream()
                    .anyMatch(variant -> refInhabited(variant, namespace, inhabited));
            case Reference reference -> refInhabited(TypeRef.of(reference.target()), namespace, inhabited);
            case io.ltr8.tson.schema.meta.InstanceTemplate template ->
                    openContainerInhabited(template, namespace, inhabited);
            default -> true; // an atom's own satisfiability is its family's question, not this one
        };
    }

    /**
     * The same guard, read off an open container's bindings rather than off a closed body: a container may be
     * empty, and if it may not, its element must be inhabited. Only the two container constructors have such a
     * guard to read -- everything else an open entry can target is judged when it closes.
     *
     * <p><b>A bound still held by a parameter counts as possibly-empty.</b> {@code <N> [tree; N]} could be
     * applied with {@code 0}, so refusing it here would reject a template on the strength of an argument
     * nobody has supplied -- and the closure that does supply one is judged on its own.
     */
    private static boolean openContainerInhabited(io.ltr8.tson.schema.meta.InstanceTemplate template,
            Map<String, TypeDefinition> namespace, Set<String> inhabited) {
        String element = switch (template.target()) {
            case "array", "set" -> "element_type";
            case "map" -> "value_type";
            default -> null;
        };
        if (element == null) {
            return true;
        }
        TemplateArgument minItems = template.bindings().get("min_items");
        if (!(minItems instanceof TemplateArgument.Value bound) || isEmptyAllowed(naturalOf(bound))) {
            return true;
        }
        return !(template.bindings().get(element) instanceof TemplateArgument.Ref ref)
                || refInhabited(ref.typeRef(), namespace, inhabited);
    }

    /** A bound as a number, or absent when its token is not one -- an unreadable bound constrains nothing here. */
    private static Optional<BigInteger> naturalOf(TemplateArgument.Value bound) {
        try {
            return Optional.of(new BigInteger(bound.value().text()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * A record needs every part it cannot do without, and one member of every group it must choose from.
     *
     * <p><b>The groups are walked separately because their members hide from the field walk</b>: §5.11 makes a
     * group's members uniformly OPTIONAL in {@code fields}, with the requirement carried by the group's own
     * state. Reading only the field list would find nothing required and call every group satisfied.
     */
    private static boolean recordInhabited(RecordBody record, Map<String, TypeDefinition> namespace,
            Set<String> inhabited) {
        Set<String> grouped = new HashSet<>();
        record.groups().forEach(group -> grouped.addAll(group.members()));
        for (RecordField field : record.fields()) {
            if (grouped.contains(field.name()) || isOptional(field)) {
                continue;
            }
            if (!refInhabited(field.type(), namespace, inhabited)) {
                return false;
            }
        }
        for (FieldGroup group : record.groups()) {
            if (group.state() != ElementState.REQUIRED) {
                continue;
            }
            boolean any = group.members().stream().anyMatch(member -> record.fields().stream()
                    .filter(field -> field.name().equals(member))
                    .anyMatch(field -> refInhabited(field.type(), namespace, inhabited)));
            if (!any) {
                return false;
            }
        }
        return true;
    }

    /**
     * A field a document may leave out places no demand on its type. Every other state does, the two that
     * carry a value included: a fixed or default value of a type nothing can satisfy does not exist either.
     */
    private static boolean isOptional(RecordField field) {
        return switch (field.state()) {
            case OPTIONAL, OPTIONAL_FIXED -> true;
            case REQUIRED, REQUIRED_DEFAULT, REQUIRED_FIXED -> false;
        };
    }

    private static boolean positionInhabited(TupleElement element, Map<String, TypeDefinition> namespace,
            Set<String> inhabited) {
        return element.state() == ElementState.OPTIONAL
                || refInhabited(element.elementType(), namespace, inhabited);
    }

    /**
     * Whether a container may be empty -- the guard that makes recursion through one terminate. {@code [tree]}
     * is satisfied by {@code []} whatever {@code tree} turns out to be; {@code [tree; 1..]} is satisfied only
     * if {@code tree} is. That single distinction is what separates ordinary recursion from the runaway kind.
     */
    private static boolean isEmptyAllowed(Optional<BigInteger> minItems) {
        return minItems.isEmpty() || minItems.get().signum() <= 0;
    }

    /**
     * A name this namespace does not hold counts as inhabited. The reference is unresolved, which {@code
     * TsonSchemaLinker.validateEntry} has already reported against this very entry -- calling it uninhabited
     * too would report the same defect twice, in words that name a different problem.
     */
    private static boolean refInhabited(TypeRef ref, Map<String, TypeDefinition> namespace,
            Set<String> inhabited) {
        return !namespace.containsKey(ref.name()) || inhabited.contains(ref.name());
    }

    /**
     * The chain from an uninhabited entry back to itself, or to whatever else it depends on, for the
     * diagnostic. Walks the demands the rules above make -- the parts a value cannot do without -- so the
     * chain shown is the one that actually has to be broken.
     */
    static List<String> cycleThrough(String name, Map<String, TypeDefinition> namespace,
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
     * first member of a group that has to be chosen from and has nothing to choose. Following the group
     * matters because its members are OPTIONAL in {@code fields}, so a chain that walked only required fields
     * would stop at the record and explain nothing.
     */
    private static String recordDependency(RecordBody record, Map<String, TypeDefinition> namespace,
            Set<String> inhabited) {
        Set<String> grouped = new HashSet<>();
        record.groups().forEach(group -> grouped.addAll(group.members()));
        for (RecordField field : record.fields()) {
            if (!grouped.contains(field.name()) && !isOptional(field)
                    && !refInhabited(field.type(), namespace, inhabited)) {
                return field.type().name();
            }
        }
        return record.groups().stream()
                .filter(group -> group.state() == ElementState.REQUIRED)
                .flatMap(group -> group.members().stream())
                .flatMap(member -> record.fields().stream().filter(field -> field.name().equals(member)))
                .map(RecordField::type)
                .filter(type -> !refInhabited(type, namespace, inhabited))
                .map(TypeRef::name).findFirst().orElse(null);
    }

    /** The first thing a definition demands that nothing satisfies -- the next link of the chain. */
    private static String firstUnsatisfiedDependency(TypeDefinition definition,
            Map<String, TypeDefinition> namespace, Set<String> inhabited) {
        if (definition == null) {
            return null;
        }
        return switch (definition.body()) {
            case RecordBody record -> recordDependency(record, namespace, inhabited);
            case ArrayBody array -> refInhabited(array.elementType(), namespace, inhabited)
                    ? null : array.elementType().name();
            case MapBody map -> refInhabited(map.valueType(), namespace, inhabited)
                    ? null : map.valueType().name();
            case io.ltr8.tson.schema.meta.TupleBody tuple -> tuple.elements().stream()
                    .filter(element -> !positionInhabited(element, namespace, inhabited))
                    .map(element -> element.elementType().name()).findFirst().orElse(null);
            case Reference reference -> refInhabited(TypeRef.of(reference.target()), namespace, inhabited)
                    ? null : reference.target();
            case io.ltr8.tson.schema.meta.InstanceTemplate template ->
                    template.bindings().values().stream()
                            .filter(TemplateArgument.Ref.class::isInstance)
                            .map(binding -> ((TemplateArgument.Ref) binding).typeRef())
                            .filter(ref -> !refInhabited(ref, namespace, inhabited))
                            .map(TypeRef::name).findFirst().orElse(null);
            default -> null; // a choice fails on every variant at once, so no single link continues the chain
        };
    }
}
