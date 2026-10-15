package io.ltr8.tson.schema.meta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The meta-kernel's {@code type => top & { supertypes?: [type_ref]  subtypes?: [type_name] }} (Part 2 §4.1,
 * SPEC-FEEDBACK.md #9, #14): the kind of the kinds that describe the shape of a data value, and the carrier of its
 * IS-A chain. {@link Atom}, {@link Product} and {@link Sum} each {@code extends Type}, as {@code atom}, {@code
 * product} and {@code sum} compose with {@code type}; {@link Data}, {@link Reference} and {@link TemplateBody} do
 * not, as their kernel entries compose with {@code top} directly -- a value, a hop and an open body carry no IS-A.
 * So a body describes a type exactly when it is an {@code instanceof Type}, and every type body has the two slots.
 *
 * <p><b>{@link #supertypes} is the complete chain</b> in a closed body: every type this one IS-A, transitively,
 * each a bare name, and empty for a fresh record, an instance and a subtracted record alike. It is {@code
 * [type_ref]} because inside held template text the same slot holds the parents as written, where one may be an
 * application ({@code <T> result<T> & { ... }}); the text closes to the chain as every other reference in it
 * closes. {@link #subtypes} is the inverse within one schema map (§3.3.4). A declaration never writes either: the
 * resolver computes both, and ingest reads them as stated, since a flat body and its chain cannot be recomputed
 * from each other.
 *
 * <p>Every body record carries the two as its first components, mirroring the constructor's flattened shape,
 * where {@code type}'s fields come first. Absent and empty are one list.
 *
 * <p>Not a base kind: {@link TypeKind} has no {@code TYPE}, and a kind is read from the base kind below it.
 */
public sealed interface Type extends Top permits Atom, Product, Sum {

    /** The IS-A chain: complete and bare in a closed body ({@link Type}'s own Javadoc). */
    List<TypeRef> supertypes();

    /** The entries of this schema map that are IS-A this one (§3.3.4). */
    List<String> subtypes();

    /** This body with its chain replaced -- every facet of its own unchanged. */
    Type withChain(List<TypeRef> supertypes, List<String> subtypes);

    /** This body with {@link #subtypes} replaced and {@link #supertypes} kept. */
    default Type withSubtypes(List<String> subtypes) {
        return withChain(supertypes(), subtypes);
    }

    /** This body with {@link #supertypes} replaced, by name, and {@link #subtypes} kept. */
    default Type withSupertypes(List<String> supertypes) {
        return withChain(supertypes.stream().map(TypeRef::of).toList(), subtypes());
    }

    /** {@link #supertypes} by name -- what a closed chain is. */
    default List<String> supertypeNames() {
        return supertypes().stream().map(TypeRef::name).toList();
    }

    /**
     * {@code value} with its chain emptied, for a comparison of what two values are rather than where each sits:
     * a core type and the kernel original it copies are one body in two schema maps, each with its own subtypes.
     */
    static Top withoutChain(Top value) {
        return value instanceof Type type ? type.withChain(List.of(), List.of()) : value;
    }

    /** {@code value}'s chain by name, empty for a value that is no type and so carries none. */
    static List<String> supertypesOf(Top value) {
        return value instanceof Type type ? type.supertypeNames() : List.of();
    }

    /** {@code value}'s subtypes, empty for a value that is no type and so carries none. */
    static List<String> subtypesOf(Top value) {
        return value instanceof Type type ? type.subtypes() : List.of();
    }

    /**
     * The entries of {@code entries} IS-A {@code name}: a type's own {@link #subtypes}, and for a family base --
     * a record-bodied template, which composes {@code top} and so has no slot to hold them -- the entries whose
     * chain names it, in map order. A family's members are the applications some declaration names, and each
     * such member's chain names the template ([TSON-SCHEMA] §5.10).
     */
    static List<String> subtypesOf(String name, Top value, Map<String, ? extends Top> entries) {
        if (value instanceof Type type) {
            return type.subtypes();
        }
        if (!(value instanceof TemplateBody)) {
            return List.of();
        }
        List<String> members = new ArrayList<>();
        entries.forEach((member, body) -> {
            if (supertypesOf(body).contains(name)) {
                members.add(member);
            }
        });
        return List.copyOf(members);
    }
}
