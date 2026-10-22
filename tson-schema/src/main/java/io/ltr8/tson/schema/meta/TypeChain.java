package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;

import java.util.List;

/**
 * The meta-kernel's {@code type_chain} ([TSON-SCHEMA] §4.1, SPEC-FEEDBACK.md #14): where an entry sits in the
 * IS-A order. Carried by every {@link Type}, as {@code type}'s one field, and asked of any {@link Top} by the
 * statics here, which answer empty for a value that carries none. Which application an entry is the instantiation
 * of is no part of it: an entry of any kind can be one, so that fact sits on the entry's key ({@code
 * TsonSchema#applicationOf}).
 *
 * <p><b>Three states, one record.</b> {@link #parents} is what was written -- the parents of held template text,
 * one of which may be an application ({@code <T> result<T> & { ... }}), and of a family-base template after it
 * closes. It is the only list that ever holds an application, and a closed type that is no template carries
 * none. {@link #supertypes} is the closed chain: every entry this one IS-A, complete to a fixed point through
 * applied parents (§5.8), each a bare name, sorted; empty for a fresh record, an instance and a subtracted
 * record alike. {@link #subtypes} is the inverse, over this schema map only (§3.3.4).
 *
 * <p>A declaration never writes the record: the resolver computes it and the linker inverts it, and ingest reads
 * it as stated, since a flat body and its chain cannot be recomputed from each other. Absent and empty are one
 * chain, and one list.
 */
@Typename(name = "type_chain")
public record TypeChain(List<TypeRef> parents, List<String> supertypes, List<String> subtypes) {

    /** The chain of a value no declaration placed: a fresh record, an instance. */
    public static final TypeChain EMPTY = new TypeChain(List.of(), List.of(), List.of());

    @Record
    public TypeChain {
        parents = parents == null ? List.of() : List.copyOf(parents);
        // Sorted and without repeats, so one chain has one spelling whichever order a resolver reached it in.
        supertypes = supertypes == null ? List.of() : supertypes.stream().sorted().distinct().toList();
        subtypes = subtypes == null ? List.of() : List.copyOf(subtypes);
    }

    /** A closed chain of {@code supertypes} and nothing else. */
    public static TypeChain of(List<String> supertypes) {
        return EMPTY.withSupertypes(supertypes);
    }

    /** {@code chain}, or {@link #EMPTY} where a bound document omitted the field. */
    public static TypeChain orEmpty(TypeChain chain) {
        return chain == null ? EMPTY : chain;
    }

    /** {@code value}'s chain, {@link TypeChain#EMPTY} for a value that carries none. */
    public static TypeChain chainOf(Top value) {
        return value instanceof Type type ? type.chain() : EMPTY;
    }

    /** {@code value}'s supertypes, empty for a value that carries no chain. */
    public static List<String> supertypesOf(Top value) {
        return chainOf(value).supertypes();
    }

    /** {@code value}'s subtypes, empty for a value that carries no chain. */
    public static List<String> subtypesOf(Top value) {
        return chainOf(value).subtypes();
    }

    /** {@code value}'s written parents, empty for a value that carries no chain. */
    public static List<TypeRef> parentsOf(Top value) {
        return chainOf(value).parents();
    }

    /** {@code value} with {@code chain} in place of its own; a value that carries none is returned as it is. */
    public static Top withChain(Top value, TypeChain chain) {
        return value instanceof Type type ? type.withChain(chain) : value;
    }

    /**
     * {@code value} with its chain emptied, for a comparison of what two values are rather than where each sits:
     * a core type and the kernel original it copies are one body in two schema maps, each with its own subtypes.
     */
    public static Top withoutChain(Top value) {
        return withChain(value, EMPTY);
    }

    /** {@code true} when nothing is stated: no parents, no chain, no inverse. */
    public boolean isEmpty() {
        return equals(EMPTY);
    }

    public TypeChain withParents(List<TypeRef> parents) {
        return new TypeChain(parents, supertypes, subtypes);
    }

    public TypeChain withSupertypes(List<String> supertypes) {
        return new TypeChain(parents, supertypes, subtypes);
    }

    public TypeChain withSubtypes(List<String> subtypes) {
        return new TypeChain(parents, supertypes, subtypes);
    }
}
