package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;

import java.util.List;

/**
 * The meta-kernel's {@code void_type} constructor: an atom with no constraint vocabulary, whose parsing
 * contract admits only the void sentinel {@code _}. Its instances are the kernel's {@code void} and core's
 * sibling of the same name, both {@code !void_type {}} -- two type entities, one contract, recognised by this
 * body rather than by either name.
 */
@Typename(name = "void_type")
public record VoidType(TypeChain chain) implements Atom {

    /** The chain normalised: absent and empty are one list. */
    @Record
    public VoidType {
        chain = TypeChain.orEmpty(chain);
    }

    /** This body with an empty IS-A chain -- a value no resolver has placed in one. */
    public VoidType() {
        this(TypeChain.EMPTY);
    }

    @Override
    public VoidType withChain(TypeChain chain) {
        return new VoidType(chain);
    }
}
