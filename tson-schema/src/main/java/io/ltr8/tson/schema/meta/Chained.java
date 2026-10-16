package io.ltr8.tson.schema.meta;

import java.util.List;
import java.util.Optional;

/**
 * The meta-kernel's {@code chained => { chain?: type_chain }} ([TSON-SCHEMA] §4.1, SPEC-FEEDBACK.md #14): a value
 * that carries an IS-A chain. {@link Type} and {@link TemplateBody} are the two, as {@code type} and {@code
 * template} compose it; {@link Data} and {@link Reference} are not -- a value of meta vocabulary and a hop carry
 * no IS-A.
 *
 * <p><b>Every question about what an entry IS-A asks this</b>, so a template -- the ABSTRACT base its members are
 * IS-A, and IS-A its own parents -- is answered the same way a closed type is. The accessors delegate to {@link
 * #chain}, and the statics answer for any {@link Top}, empty for one that carries no chain.
 */
public sealed interface Chained permits Type, TemplateBody {

    /** Where this value sits in the IS-A order ({@link TypeChain}). */
    TypeChain chain();

    /** This value with its chain replaced -- everything of its own unchanged. */
    Chained withChain(TypeChain chain);

    /** The parents as written: held text's, and a family-base template's ({@link TypeChain#parents}). */
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

    /** The application this entry is the instantiation of (§8.2). */
    default Optional<TypeRef> application() {
        return chain().application();
    }

    default Chained withSupertypes(List<String> supertypes) {
        return withChain(chain().withSupertypes(supertypes));
    }

    default Chained withSubtypes(List<String> subtypes) {
        return withChain(chain().withSubtypes(subtypes));
    }

    /** {@code value}'s chain, {@link TypeChain#EMPTY} for a value that carries none. */
    static TypeChain chainOf(Top value) {
        return value instanceof Chained chained ? chained.chain() : TypeChain.EMPTY;
    }

    /** {@code value}'s supertypes, empty for a value that carries no chain. */
    static List<String> supertypesOf(Top value) {
        return chainOf(value).supertypes();
    }

    /** {@code value}'s subtypes, empty for a value that carries no chain. */
    static List<String> subtypesOf(Top value) {
        return chainOf(value).subtypes();
    }

    /** {@code value}'s written parents, empty for a value that carries no chain. */
    static List<TypeRef> parentsOf(Top value) {
        return chainOf(value).parents();
    }

    /** The application {@code value} is the instantiation of, if it records one. */
    static Optional<TypeRef> applicationOf(Top value) {
        return chainOf(value).application();
    }

    /** {@code value} with {@code chain} in place of its own; a value that carries none is returned as it is. */
    static Top withChain(Top value, TypeChain chain) {
        return value instanceof Chained chained ? (Top) chained.withChain(chain) : value;
    }

    /**
     * {@code value} with its chain emptied, for a comparison of what two values are rather than where each sits:
     * a core type and the kernel original it copies are one body in two schema maps, each with its own subtypes.
     */
    static Top withoutChain(Top value) {
        return withChain(value, TypeChain.EMPTY);
    }
}
