package io.ltr8.tson.json.tree;

import java.util.List;
import java.util.Optional;

/** A JSON array: values in order, which here does mean order. */
public record JsonArray(List<JsonValue> elements) implements JsonValue {

    /** Defensively copied, so a caller's later edit cannot reach this value. */
    public JsonArray {
        elements = List.copyOf(elements);
    }

    public static JsonArray of(List<JsonValue> elements) {
        return new JsonArray(elements);
    }

    public static JsonArray empty() {
        return new JsonArray(List.of());
    }

    @Override
    public JsonValue get(int index) {
        if (index < 0 || index >= elements.size()) {
            throw new JsonValueException("this array has %d element%s, so index %d is not one of them"
                    .formatted(elements.size(), elements.size() == 1 ? "" : "s", index));
        }
        return elements.get(index);
    }

    @Override
    public Optional<JsonValue> tryGet(int index) {
        return index >= 0 && index < elements.size() ? Optional.of(elements.get(index)) : Optional.empty();
    }

    @Override
    public List<JsonValue> asList() {
        return elements;
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder("[");
        for (JsonValue element : elements) {
            if (out.length() > 1) {
                out.append(',');
            }
            out.append(element);
        }
        return out.append(']').toString();
    }
}
