package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An instance of the meta-kernel's {@code kind_type} constructor, resolved ([TSON-SCHEMA] §4.2): the atom whose
 * values are the names of the types built with the constructor {@link #of}. {@code kind<uuid_type>} and {@code
 * !kind_type { of: uuid_type }} are one instance, and a value at a position typed by it is a type name that
 * resolves to one of its {@link #members} -- the name, never a value of the type it names.
 *
 * <p>An enum is an atom whose value set is a listed set of names; a kind is an atom whose value set is a computed
 * one. {@link #of} names a constructor of the writer's structure namespace, never a type of its type-name
 * namespace, and is held as the name it was written. Which entries are members is a question about the governing
 * schema's closure, which this body cannot see, so the linker answers it and writes the answer here.
 *
 * <p><b>{@link #members} is a derived index</b>, as {@link TypeChain#subtypes} is: absent on a declaration,
 * computed at link, sorted, and discarded and recomputed on ingest.
 *
 * <p><b>Equality is {@link #of} and the chain, never the members.</b> The index says what the closure holds rather
 * than which kind this is, so two schemas that both apply {@code kind<uuid_type>} hold one instance with different
 * members, and an importer that re-derives an entry an import already minted unifies with it rather than colliding
 * (§8.2).
 */
@Typename(name = "kind_type")
public record KindBody(TypeChain chain, String of, Optional<List<String>> members) implements Atom {

    @Record
    public KindBody {
        chain = TypeChain.orEmpty(chain);
        Objects.requireNonNull(of, "of");
        members = members == null ? Optional.empty() : members.map(List::copyOf);
    }

    /** A kind as an author declares it, placed in no chain -- the linker derives {@link #members}. */
    public KindBody(String of) {
        this(TypeChain.EMPTY, of, Optional.empty());
    }

    @Override
    public KindBody withChain(TypeChain chain) {
        return new KindBody(chain, of, members);
    }

    /** This kind with its derived index: {@code members}, sorted. */
    public KindBody linked(List<String> members) {
        return new KindBody(chain, of, Optional.of(members.stream().sorted().toList()));
    }

    /** The members, or none where linking has not computed them. */
    public List<String> memberNames() {
        return members.orElse(List.of());
    }

    /**
     * {@inheritDoc}
     *
     * <p>A kind's one authored facet is {@link #of}, and it is what the kind is: a refinement restating another
     * names another set of types. The members are derived, so nothing else is compared.
     */
    @Override
    public List<String> constraintsCheck(Atom refined) {
        if (!(refined instanceof KindBody other)) {
            return List.of("refines a kind with " + refined.getClass().getSimpleName());
        }
        return of.equals(other.of) ? List.of() : List.of("of '" + other.of + "' replaces the source's own '" + of
                + "'; a kind is the set of types built with its constructor, fixed where it is constructed");
    }

    /** Compares {@link #of} and the chain, never the members -- see this class's own Javadoc for why. */
    @Override
    public boolean equals(Object o) {
        return o instanceof KindBody other && of.equals(other.of) && chain.equals(other.chain);
    }

    /** {@link #of} and the chain, as {@link #equals} compares them. */
    @Override
    public int hashCode() {
        return 31 * of.hashCode() + chain.hashCode();
    }
}
