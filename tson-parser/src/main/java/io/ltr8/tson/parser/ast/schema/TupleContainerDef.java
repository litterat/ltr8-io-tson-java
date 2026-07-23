package io.ltr8.tson.parser.ast.schema;

import java.util.List;

/**
 * {@code "[" element-type 1*(separator element-type) "]"} (Part 2 §12.1, §5.3) -- a
 * declaration-level tuple type, whose positions (unlike {@link InlineTupleRef}'s) may each carry
 * their own {@code ?} for {@code OPTIONAL} position state.
 */
public record TupleContainerDef(List<ElementType> elementTypes) implements ContainerDef {

    public TupleContainerDef {
        elementTypes = List.copyOf(elementTypes);
        if (elementTypes.size() < 2) {
            throw new IllegalArgumentException("a tuple requires at least two positions, got " + elementTypes.size());
        }
    }
}
