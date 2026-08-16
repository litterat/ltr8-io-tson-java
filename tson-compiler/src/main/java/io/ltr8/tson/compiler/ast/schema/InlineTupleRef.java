package io.ltr8.tson.compiler.ast.schema;

import java.util.List;

/**
 * {@code "[" type-ref 1*(separator type-ref) "]"} (Part 2 §12.1, §5.3) -- the inline tuple sugar:
 * two or more positions, all {@code REQUIRED} (an optional tuple position is declaration-level-only
 * syntax, modeled by {@link TupleContainerDef} instead). {@code SchemaDesugarer} hoists it into a
 * declaration of the {@code !tuple { elements: [...] }} it denotes and replaces it with a bare
 * reference to that, so it never reaches the resolver as itself.
 */
public record InlineTupleRef(List<TypeRef> elementTypes) implements TypeRef {

    public InlineTupleRef {
        elementTypes = List.copyOf(elementTypes);
        if (elementTypes.size() < 2) {
            throw new IllegalArgumentException("a tuple requires at least two positions, got " + elementTypes.size());
        }
    }
}
