package io.ltr8.tson.compiler.ast.schema;

import java.util.List;

/**
 * {@code "[" element-type 1*(separator element-type) "]"} (Part 2 §12.1, §5.3) -- a tuple type, two or more
 * positions, legal at every type expression position. Each position may carry its own {@code ?} for
 * {@code OPTIONAL} position state.
 *
 * <p>Distinguished from {@link ArrayExpr} by arity alone, which is what the bracket production's two
 * alternatives decide: one element (with or without a size) is an array, two or more a tuple.
 */
public record TupleExpr(List<ElementType> elementTypes) implements TypeExpr {

    public TupleExpr {
        elementTypes = List.copyOf(elementTypes);
        if (elementTypes.size() < 2) {
            throw new IllegalArgumentException("a tuple requires at least two positions, got " + elementTypes.size());
        }
    }
}
