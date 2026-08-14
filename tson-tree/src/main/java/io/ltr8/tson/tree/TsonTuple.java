package io.ltr8.tson.tree;

import java.util.List;
import java.util.Optional;

/**
 * A tuple node -- a fixed-arity, positionally-typed sequence (§5.4). Structurally like {@link TsonArray}
 * (ordered elements, bounds-safe {@link #get(int)}) but a distinct kind: only a <b>schema-driven</b> read
 * produces one, since the grammar has no tuple/array distinction (a schemaless read yields {@link TsonArray}).
 */
public record TsonTuple(List<TsonValue> elements, Optional<String> typeRef, List<TsonAnnotation> annotations)
        implements TsonValue {

    public TsonTuple {
        elements = List.copyOf(elements);
        annotations = List.copyOf(annotations);
    }

    public static TsonTuple of(TsonValue... elements) {
        return new TsonTuple(List.of(elements), Optional.empty(), List.of());
    }

    @Override
    public boolean isTuple() {
        return true;
    }

    @Override
    public TsonValue get(int index) {
        return index >= 0 && index < elements.size() ? elements.get(index) : TsonMissing.atIndex(index);
    }
}
