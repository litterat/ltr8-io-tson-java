package io.ltr8.tson.compiler.tree;

import java.util.List;
import java.util.Optional;

/**
 * A tuple node -- a fixed-arity, positionally-typed sequence (§5.4). Structurally like {@link ArrayNode}
 * (ordered elements, bounds-safe {@link #get(int)}) but a distinct kind: only a <b>schema-driven</b> read
 * produces one, since the grammar has no tuple/array distinction (a schemaless read yields {@link ArrayNode}).
 */
public record TupleNode(List<TsonNode> elements, Optional<String> typeRef, List<TsonAnnotation> annotations)
        implements TsonNode {

    public TupleNode {
        elements = List.copyOf(elements);
        annotations = List.copyOf(annotations);
    }

    public static TupleNode of(TsonNode... elements) {
        return new TupleNode(List.of(elements), Optional.empty(), List.of());
    }

    @Override
    public boolean isTuple() {
        return true;
    }

    @Override
    public TsonNode get(int index) {
        return index >= 0 && index < elements.size() ? elements.get(index) : MissingNode.instance();
    }
}
