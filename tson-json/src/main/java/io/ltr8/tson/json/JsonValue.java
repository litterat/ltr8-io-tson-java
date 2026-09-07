package io.ltr8.tson.json;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A JSON value: the sealed root of a pure, immutable tree over RFC 8259's six value kinds.
 *
 * <p><b>Shaped and named after JEP 540</b>, the JDK's forthcoming {@code jdk.incubator.json} — the same
 * six subtypes, the same {@code get}/{@code tryGet} navigation and {@code as*} conversion vocabulary,
 * the same {@code of} factories — so a consumer learns one API across the two and a bridge is later a
 * mapping rather than a rewrite. Where this differs, {@code docs/json-encoding.md} says why.
 *
 * <p><b>This is a faithful JSON model, not a TSON one.</b> {@link JsonNull} is a value here, because at
 * this layer it is one; [TSON-JSON] §7 makes JSON null the absent sentinel's spelling <em>at a typed
 * position</em>, and a tree read with no schema binding has none. That reading arrives with the
 * schema-directed decode of §5–§8 and nowhere earlier.
 *
 * <p><b>Nodes carry no source position</b>, so equality is over content and two structurally equal
 * documents parsed from different sources are equal. It is the same shape {@code TsonValue} takes and
 * for the same reason — a value model holds values — and it costs less than it appears to: a parse
 * failure and a duplicate member are reported by {@link Json#parse} with a {@link JsonPosition}
 * already, and the schema-directed decode streams events, which carry positions, rather than walking a
 * tree. A {@link JsonValueException} therefore names the step and the value rather than a line.
 *
 * <p>The throwing accessors below are the ergonomic path and each has a non-throwing peer. Both
 * families live here rather than on the subtypes, JEP 540's own arrangement: {@code
 * root.get("a").get(0).asLong()} reads as one expression precisely because no cast stands between the
 * steps.
 */
public sealed interface JsonValue
        permits JsonObject, JsonArray, JsonString, JsonNumber, JsonBoolean, JsonNull {

    // ── Navigation ───────────────────────────────────────────────────────

    /** The value of member {@code name}. @throws JsonValueException if this is not an object, or has no such member */
    default JsonValue get(String name) {
        throw new JsonValueException("%s has no members, so '%s' cannot be read from it".formatted(kind(), name));
    }

    /** The element at {@code index}. @throws JsonValueException if this is not an array, or has no such element */
    default JsonValue get(int index) {
        throw new JsonValueException("%s has no elements, so index %d cannot be read from it".formatted(kind(), index));
    }

    /** The value of member {@code name}, or empty if this is not an object or has no such member. */
    default Optional<JsonValue> tryGet(String name) {
        return Optional.empty();
    }

    /** The element at {@code index}, or empty if this is not an array or has no such element. */
    default Optional<JsonValue> tryGet(int index) {
        return Optional.empty();
    }

    /**
     * This value, or empty if it is {@link JsonNull} — the one-call form of "a value, if there is one".
     *
     * <p>JEP 540's own way of collapsing JSON's null into an absence at the point a caller reads it,
     * and the nearest thing this layer has to §7's rule. It is a caller's convenience and not that
     * rule: §7 is decided by the <em>position's declared state</em>, which nothing here holds.
     */
    default Optional<JsonValue> tryValue() {
        return Optional.of(this);
    }

    // ── Conversion ───────────────────────────────────────────────────────

    /** @throws JsonValueException unless this is a {@link JsonString} */
    default String asString() {
        throw notA("a string");
    }

    /** @throws JsonValueException unless this is a {@link JsonNumber} exactly representable as an {@code int} */
    default int asInt() {
        throw notA("a number");
    }

    /** @throws JsonValueException unless this is a {@link JsonNumber} exactly representable as a {@code long} */
    default long asLong() {
        throw notA("a number");
    }

    /** @throws JsonValueException unless this is a {@link JsonNumber}; rounds to the nearest {@code double} */
    default double asDouble() {
        throw notA("a number");
    }

    /** @throws JsonValueException unless this is a {@link JsonBoolean} */
    default boolean asBoolean() {
        throw notA("a boolean");
    }

    /** This object's members in document order, unmodifiable. @throws JsonValueException unless this is a {@link JsonObject} */
    default Map<String, JsonValue> asMap() {
        throw notA("an object");
    }

    /** This array's elements in order, unmodifiable. @throws JsonValueException unless this is a {@link JsonArray} */
    default List<JsonValue> asList() {
        throw notA("an array");
    }

    // ── Serialization ────────────────────────────────────────────────────

    /**
     * This value as compact RFC 8259 JSON, on one line.
     *
     * <p>Within [TSON-JSON] §3.1's profile in both directions: what {@link Json#parse} accepts, this
     * emits, and what this emits {@link Json#parse} accepts — §9.2's round trip, which is the
     * conformance test rather than a separate rule set. A {@link JsonNumber} re-emits its own digits
     * (§5.3), never a value routed through a host type.
     */
    @Override
    String toString();

    // ── Helpers for the defaults above ───────────────────────────────────

    /** How this value names itself in a message: "an object", "a number", and so on. */
    private String kind() {
        return switch (this) {
            case JsonObject ignored -> "an object";
            case JsonArray ignored -> "an array";
            case JsonString ignored -> "a string";
            case JsonNumber ignored -> "a number";
            case JsonBoolean ignored -> "a boolean";
            case JsonNull ignored -> "null";
        };
    }

    private JsonValueException notA(String wanted) {
        return new JsonValueException("this value is %s, not %s".formatted(kind(), wanted));
    }
}
