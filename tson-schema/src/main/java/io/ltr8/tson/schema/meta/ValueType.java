package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;

import java.util.List;

/**
 * The meta-kernel's {@code value_type} constructor: an atom with no constraint vocabulary, whose one instance
 * is {@code value => !value_type {}}, the escape hatch. Its contract is the encoding's -- a value position is
 * read by whatever base type resolution the encoding defines -- so the body carries nothing, and the
 * constructor alone is what tells {@code value} apart from every other atom.
 */
@Typename(name = "value_type")
public record ValueType(TypeChain chain) implements Atom {

    /** The chain normalised: absent and empty are one list. */
    @Record
    public ValueType {
        chain = TypeChain.orEmpty(chain);
    }

    /** This body with an empty IS-A chain -- a value no resolver has placed in one. */
    public ValueType() {
        this(TypeChain.EMPTY);
    }

    @Override
    public ValueType withChain(TypeChain chain) {
        return new ValueType(chain);
    }
}
