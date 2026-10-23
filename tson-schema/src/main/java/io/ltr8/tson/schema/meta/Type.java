package io.ltr8.tson.schema.meta;

import java.util.List;

/**
 * The meta-kernel's {@code type => abstract top & { chain?: type_chain }} (Part 2 §4.1, SPEC-FEEDBACK.md #9, #14,
 * #15): the
 * kind of the kinds that describe the shape of a data value. {@link Atom}, {@link Product} and {@link Sum} each
 * {@code extends Type}, as {@code atom}, {@code product} and {@code sum} compose with {@code type}; {@link Reference}
 * and {@link Namespace} do not, as their kernel entries compose with {@code top} directly. So a body describes a
 * type exactly when it is an {@code instanceof Type}, and every type body carries its chain ({@link TypeChain}).
 *
 * <p><b>Every question about what an entry IS-A asks this</b>, so a record template -- the ABSTRACT family base
 * its members are IS-A, and IS-A its own parents -- is answered the same way a closed type is. {@link Namespace},
 * {@link Reference} and {@link TemplateBody} carry no chain: a value, a hop and an abbreviation have no IS-A.
 *
 * <p>Every body record carries {@code chain} as its first component, mirroring the constructor's flattened
 * shape, where {@code type}'s field comes first.
 *
 * <p>Not a base kind: {@link EntryKind} has no {@code TYPE}, and a kind is read from the base kind below it.
 */
public sealed interface Type extends Top permits Atom, Product, Sum {

    Type withChain(TypeChain chain);

    default Type withSupertypes(List<String> supertypes) {
        return withChain(chain().withSupertypes(supertypes));
    }

    default Type withSubtypes(List<String> subtypes) {
        return withChain(chain().withSubtypes(subtypes));
    }

    /** Where this value sits in the IS-A order ({@link TypeChain}). */
    TypeChain chain();

    /** The parents as written, a record template's included ({@link TypeChain#parents}). */
    default List<TypeRef> parents() {
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
