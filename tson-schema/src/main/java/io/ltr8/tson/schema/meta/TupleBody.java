package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;

import java.util.List;

/**
 * The meta-kernel's {@code tuple} constructor's own vocabulary, resolved (Part 2 §4.2, §5.3,
 * §8.1) -- {@code access_pattern}/{@code size_type} are fixed ({@code INDEX}/{@code FIXED}) and
 * never appear in output. {@code elements}' positional order is significant (§5.3: a tuple's
 * positions are fixed-arity and ordered), unlike {@link EnumBody}'s members and the set-like IS-A chain
 * every {@link Type} carries.
 */
@Typename(name = "tuple")
public record TupleBody(TypeChain chain,
        List<TupleElement> elements) implements Product {

    @Record

    public TupleBody {
        chain = TypeChain.orEmpty(chain);
        elements = List.copyOf(elements);
    }

    /** This body with an empty IS-A chain -- a value no resolver has placed in one. */
    public TupleBody(List<TupleElement> elements) {
        this(TypeChain.EMPTY, elements);
    }

    @Override
    public TupleBody withChain(TypeChain chain) {
        return new TupleBody(chain, elements);
    }
}
