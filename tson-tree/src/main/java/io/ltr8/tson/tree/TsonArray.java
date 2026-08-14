package io.ltr8.tson.tree;

import java.util.List;
import java.util.Optional;

/**
 * An array node -- ordered, variable-length elements of a single element type (§5.3). {@link #get(int)} is
 * bounds-safe (out of range yields {@link TsonMissing}). Distinct from {@link TsonTuple}, which a
 * schema-driven read produces for a fixed-arity, positionally-typed sequence.
 */
public record TsonArray(List<TsonValue> elements, Optional<String> typeRef, List<TsonAnnotation> annotations)
        implements TsonValue {

    public TsonArray {
        elements = List.copyOf(elements);
        annotations = List.copyOf(annotations);
    }

    public static TsonArray of(TsonValue... elements) {
        return new TsonArray(List.of(elements), Optional.empty(), List.of());
    }

    @Override
    public boolean isArray() {
        return true;
    }

    @Override
    public TsonValue get(int index) {
        return index >= 0 && index < elements.size() ? elements.get(index) : TsonMissing.instance();
    }
}
