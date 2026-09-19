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

    /**
     * Defensively copied into a {@link LinkedHashMap}, so a caller's later edit cannot reach this value -- except
     * a {@link Builder}'s, which nothing else holds and which is therefore taken as it is.
     */
    public JsonObject {
        members = Collections.unmodifiableMap(members instanceof Owned owned ? owned : new LinkedHashMap<>(members));
    }

    /** An object of about {@code expectedSize} members, assembled in order. */
    public static Builder builder(int expectedSize) {
        return new Builder(expectedSize);
    }

    /**
     * Assembles an object whose members nothing else holds, so {@link #build} hands them over rather than having
     * the constructor copy them -- which is the difference, per object, between one map and two. A reader
     * building a tree assembles every object it reads, so the copy was paid on every object of every document.
     *
     * <p>Single use: {@link #build} ends it, and any call after that is refused, which is what keeps the handed-over
     * map out of reach.
     */
    public static final class Builder {

        private Owned members;

        private Builder(int expectedSize) {
            this.members = new Owned(expectedSize);
        }

        /** Adds a member, answering the value it replaced -- null for a name not seen before -- as {@code Map.put} does. */
        public JsonValue put(String name, JsonValue value) {
            return live().put(name, value);
        }

        public JsonObject build() {
            Owned built = live();
            members = null;
            return new JsonObject(built);
        }

        private Owned live() {
            if (members == null) {
                throw new IllegalStateException("this builder has already built its object");
            }
            return members;
        }
    }

    /** A {@link Builder}'s map: of a type only the builder creates, which is what lets the constructor trust it. */
    private static final class Owned extends LinkedHashMap<String, JsonValue> {

        @java.io.Serial
        private static final long serialVersionUID = 1L;

        Owned(int expectedSize) {
            super(Math.max(4, (int) (expectedSize / 0.75f) + 1));
        }
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
