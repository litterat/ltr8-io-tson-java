package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;
import io.ltr8.annotation.Unbound;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An instance of the meta-kernel's {@code reference} constructor, resolved ([TSON-SCHEMA] §4.2, §5.12): the atom
 * whose values are names, each naming an entry whose value's type, past its alias hops, IS-A {@link #of}. {@code
 * kind<record>}, {@code ref<record>} and {@code !reference { of: record }} are one instance; the two templates are
 * spellings, and the slot's meaning comes from {@link #of} and never from which one was written.
 *
 * <p><b>What {@link #of} names decides what the slot holds.</b> Where it is a constructor ({@code top} or IS-A it),
 * the values it admits are types, and the slot holds a type's name, delivered to a host as a {@link TypeRef} that
 * takes part in structural identity. Where it is any other type, the values it admits are values, and the slot holds
 * a value's qualified name, delivered as the referent ({@link Ref}). That fact is the namespace's, so the linker
 * derives it ({@link #namesTypes}).
 *
 * <p><b>The {@link #index} is derived and never written.</b> It is absent on a declaration and computed at link over
 * the governing schema's closure: whether {@code of} names a constructor, and, for one that does, the names it admits,
 * sorted. Membership is the linker's judgement, so resolved output carries {@code of} alone.
 *
 * <p><b>Equality is {@link #of} and the chain, never the index.</b> Two schemas that both apply {@code kind<uuid_type>}
 * hold one instance with different members, and an importer that re-derives an entry an import already minted unifies
 * with it rather than colliding (§8.2).
 */
@Typename(name = "reference")
public record ReferenceBody(TypeChain chain, String of, @Unbound Optional<Index> index) implements Atom {

    /**
     * What the linker derives for one reference: whether {@code of} names a constructor, so the values admitted are
     * types, and the members where it does -- the names of the closure the slot admits, sorted.
     */
    public record Index(boolean namesTypes, List<String> members) {

        public Index {
            members = List.copyOf(members);
        }
    }

    @Record
    public ReferenceBody {
        chain = TypeChain.orEmpty(chain);
        Objects.requireNonNull(of, "of");
        index = index == null ? Optional.empty() : index;
    }

    /** A reference as an author declares it, placed in no chain -- the linker derives its {@link #index}. */
    public ReferenceBody(String of) {
        this(TypeChain.EMPTY, of, Optional.empty());
    }

    @Override
    public ReferenceBody withChain(TypeChain chain) {
        return new ReferenceBody(chain, of, index);
    }

    /** This reference with its derived index: {@code namesTypes}, and the members sorted. */
    public ReferenceBody linked(boolean namesTypes, List<String> members) {
        return new ReferenceBody(chain, of, Optional.of(new Index(namesTypes, members.stream().sorted().toList())));
    }

    /**
     * Whether {@code of} names a constructor, so a value is a type's name. True where linking has not run, which
     * every reader of a value reads after: an unlinked body then reads as a kind with no members.
     */
    public boolean namesTypes() {
        return index.map(Index::namesTypes).orElse(true);
    }

    /** The names a slot that names types admits, or none where linking has not computed them. */
    public List<String> memberNames() {
        return index.map(Index::members).orElse(List.of());
    }

    /**
     * {@inheritDoc}
     *
     * <p>A reference's one authored facet is {@link #of}, and it is what the reference is: a refinement restating
     * another names another set. The index is derived, so nothing else is compared.
     */
    @Override
    public List<String> constraintsCheck(Atom refined) {
        if (!(refined instanceof ReferenceBody other)) {
            return List.of("refines a reference with " + refined.getClass().getSimpleName());
        }
        return of.equals(other.of) ? List.of() : List.of("of '" + other.of + "' replaces the source's own '" + of
                + "'; a reference admits the names of one type's values, fixed where it is constructed");
    }

    /** Compares {@link #of} and the chain, never the index -- see this class's own Javadoc for why. */
    @Override
    public boolean equals(Object o) {
        return o instanceof ReferenceBody other && of.equals(other.of) && chain.equals(other.chain);
    }

    /** {@link #of} and the chain, as {@link #equals} compares them. */
    @Override
    public int hashCode() {
        return 31 * of.hashCode() + chain.hashCode();
    }

    /** {@link #of} and the chain, as {@link #equals} compares them: the derived index is no part of what this is. */
    @Override
    public String toString() {
        return "ReferenceBody[chain=" + chain + ", of=" + of + "]";
    }
}
