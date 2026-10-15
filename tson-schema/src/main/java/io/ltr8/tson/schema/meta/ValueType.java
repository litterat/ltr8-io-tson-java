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
public record ValueType(List<TypeRef> supertypes, List<String> subtypes) implements Atom {

    /** The chain normalised: absent and empty are one list. */
    @Record
    public ValueType {
        supertypes = Chains.copy(supertypes);
        subtypes = Chains.copy(subtypes);
    }

    /** This body with an empty IS-A chain -- a value no resolver has placed in one. */
    public ValueType() {
        this(List.of(), List.of());
    }

    @Override
    public ValueType withChain(List<TypeRef> supertypes, List<String> subtypes) {
        return new ValueType(supertypes, subtypes);
    }
}
