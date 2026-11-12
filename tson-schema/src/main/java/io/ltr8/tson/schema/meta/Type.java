package io.ltr8.tson.schema.meta;

import java.util.List;

/**
 * The kernel's {@code type => abstract top & { chain?: meta.type_chain }} (Part 2 §4.1): the
 * kind of the kinds that describe the shape of a data value. {@link Atom}, {@link Product} and {@link Sum} each
 * {@code extends Type}, as {@code atom}, {@code product} and {@code sum} compose with {@code type}, and {@link
 * ApplyBody}, an application standing for a type that exists only once its open record is applied, composes {@code
 * type} directly and has no kind; {@link Alias}
 * and {@link Namespace} do not, as their kernel entries compose with {@code top} directly. So a body describes a
 * type exactly when it is an {@code instanceof Type}, and every type body carries its chain ({@link TypeChain}).
 *
 * <p><b>Every question about what an entry IS-A asks this.</b> An open record carries a chain too, recording what its
 * written parents reach, which each application inherits; the open record itself is no type (§5.10) and is in no
 * {@code subtypes} index. {@link Namespace} and {@link Alias} carry no chain: a value and a hop have no IS-A.
 *
 * <p>Every body record carries {@code chain} as its first component, mirroring the constructor's flattened
 * shape, where {@code type}'s field comes first.
 *
 * <p>Not a base kind: {@link EntryKind} has no {@code TYPE}, and a kind is read from the base kind below it.
 */
public sealed interface Type extends Top permits Atom, Product, Sum, ApplyBody {

    Type withChain(TypeChain chain);

    default Type withSupertypes(List<String> supertypes) {
        return withChain(chain().withSupertypes(supertypes));
    }

    default Type withSubtypes(List<String> subtypes) {
        return withChain(chain().withSubtypes(subtypes));
    }

    /** Where this value sits in the IS-A order ({@link TypeChain}). */
    TypeChain chain();

    /** The parents as written, an open record's included ({@link TypeChain#parents}). */
    default List<TypeExpr> parents() {
        return chain().parents();
    }

    /** Every entry this one IS-A, complete and by name ({@link TypeChain#supertypes}). */
    default List<String> supertypes() {
        return chain().supertypes();
    }

    /** The entries of this schema map IS-A this one (§3.3.4). */
    default List<String> subtypes() {
        return chain().subtypes();
    }
}
