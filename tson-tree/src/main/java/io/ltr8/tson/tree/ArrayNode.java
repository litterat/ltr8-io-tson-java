package io.ltr8.tson.tree;

import java.util.List;
import java.util.Optional;

/**
 * An array node -- ordered, variable-length elements of a single element type (§5.3). {@link #get(int)} is
 * bounds-safe (out of range yields {@link MissingNode}). Distinct from {@link TupleNode}, which a
 * schema-driven read produces for a fixed-arity, positionally-typed sequence.
 */
public record ArrayNode(List<TsonNode> elements, Optional<String> typeRef, List<TsonAnnotation> annotations)
        implements TsonNode {

    public ArrayNode {
        elements = List.copyOf(elements);
        annotations = List.copyOf(annotations);
    }

    public static ArrayNode of(TsonNode... elements) {
        return new ArrayNode(List.of(elements), Optional.empty(), List.of());
    }

    @Override
    public boolean isArray() {
        return true;
    }

    @Override
    public TsonNode get(int index) {
        return index >= 0 && index < elements.size() ? elements.get(index) : MissingNode.instance();
    }
}
