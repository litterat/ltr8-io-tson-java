package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;

/**
 * The kernel's {@code declared_type} constructor: the open sum over the governing closure, whose one instance is
 * the kernel's {@code declared}, the type of a schema map's values. A value at a position of this type names its own
 * type, by its tag, among the types the governing schema declares or imports, and is read in full against it
 * ([TSON-SCHEMA] §7.8).
 *
 * <p>The distinction against {@link ChoiceBody} is closed against open. A choice enumerates its variants; a
 * {@code declared_type} instance draws them from the closure, which is why {@code disjoint} is absent here as on
 * every non-choice sum ([TSON-SCHEMA] §8.1) and why the discrimination predicate gives one no class.
 *
 * <p>The body carries nothing: what the position admits is the closure the entry belongs to, known when the
 * schema compiles, so the constructor alone is what tells {@code declared} apart from every other sum.
 */
@Typename(name = "declared_type")
public record DeclaredType(TypeChain chain) implements Sum {

    /** The chain normalised: absent and empty are one list. */
    @Record
    public DeclaredType {
        chain = TypeChain.orEmpty(chain);
    }

    /** This body with an empty IS-A chain -- a value no resolver has placed in one. */
    public DeclaredType() {
        this(TypeChain.EMPTY);
    }

    @Override
    public DeclaredType withChain(TypeChain chain) {
        return new DeclaredType(chain);
    }
}
