package io.ltr8.tson.json.tree;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A JSON object: member names to values, in document order.
 *
 * <p><b>Order is preserved and means nothing.</b> [TSON-JSON] §6.1.6 makes member order presentation --
 * "a decoder that requires an order has invented a rule this document does not contain" -- so nothing
 * reads it; keeping it is what lets a document re-emit as it arrived, and what makes a diff of two
 * round-tripped documents about their content.
 *
 * <p>Names are unique: §3.1 makes a repeat an error, and {@link io.ltr8.tson.json.Json#parse Json.parse} refuses one before
 * a
 * {@code JsonObject} is ever built. Equality is therefore over the member set and not over the order.
 */
public record JsonObject(Map<String, JsonValue> members) implements JsonValue {

    /** Defensively copied into a {@link LinkedHashMap}, so a caller's later edit cannot reach this value. */
    public JsonObject {
        members = Collections.unmodifiableMap(new LinkedHashMap<>(members));
    }

    public static JsonObject of(Map<String, JsonValue> members) {
        return new JsonObject(members);
    }

    public static JsonObject empty() {
        return new JsonObject(Map.of());
    }

    @Override
    public JsonValue get(String name) {
        JsonValue member = members.get(name);
        if (member == null) {
            throw new JsonValueException("this object has no member '%s'; it has %s"
                    .formatted(name, members.isEmpty() ? "none" : members.keySet()));
        }
        return member;
    }

    @Override
    public Optional<JsonValue> tryGet(String name) {
        return Optional.ofNullable(members.get(name));
    }

    @Override
    public Map<String, JsonValue> asMap() {
        return members;
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder("{");
        members.forEach((name, value) -> {
            if (out.length() > 1) {
                out.append(',');
            }
            JsonText.quote(name, out);
            out.append(':').append(value);
        });
        return out.append('}').toString();
    }
}
