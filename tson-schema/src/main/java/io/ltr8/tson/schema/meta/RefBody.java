package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;
import java.util.List;
import java.util.Objects;

/**
 * The kernel's {@code ref_type}: a reference to a value, an atom whose values are qualified names naming a value whose
 * type is {@code of} or IS-A it. {@code of} names a type of the writer's own namespace, never a constructor: a
 * reference to a type is a kind ({@link KindBody}). The referent is judged when the schema links ({@code
 * ValuesByName}), and a host receives the name as written ({@link Ref}), with the value delivered at link.
 */
@Typename(name = "ref_type")
public record RefBody(TypeChain chain, String of) implements Atom {

    @Record
    public RefBody {
        chain = TypeChain.orEmpty(chain);
        Objects.requireNonNull(of, "of");
    }

    public RefBody(String of) {
        this(TypeChain.EMPTY, of);
    }

    @Override
    public RefBody withChain(TypeChain chain) {
        return new RefBody(chain, of);
    }

    /** {@code of} is fixed where the reference is constructed: a refinement may restate it and never move it. */
    @Override
    public List<String> constraintsCheck(Atom refined) {
        if (!(refined instanceof RefBody other)) {
            return List.of("refines a reference with " + refined.getClass().getSimpleName());
        }
        return of.equals(other.of) ? List.of() : List.of("of '" + other.of + "' replaces the source's own '" + of
                + "'; a reference names values of one type, fixed where it is constructed");
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RefBody other && of.equals(other.of) && chain.equals(other.chain);
    }

    @Override
    public int hashCode() {
        return 31 * of.hashCode() + chain.hashCode();
    }
}
