package io.ltr8.tson.parser.ast.schema;

import java.util.List;

/**
 * {@code "[" type-ref 1*(separator type-ref) "]"} (Part 2 §12.1, §5.3) -- the inline tuple sugar:
 * two or more positions, all {@code REQUIRED} (an optional tuple position is declaration-level-only
 * syntax, modeled by {@link TupleContainerDef} instead). Desugars to {@code !tuple { elements:
 * [...] }} at resolution.
 */
public record InlineTupleRef(List<TypeRef> elementTypes) implements TypeRef {

    public InlineTupleRef {
        elementTypes = List.copyOf(elementTypes);
        if (elementTypes.size() < 2) {
            throw new IllegalArgumentException("a tuple requires at least two positions, got " + elementTypes.size());
        }
    }
}
