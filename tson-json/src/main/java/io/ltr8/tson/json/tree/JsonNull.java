package io.ltr8.tson.json.tree;

import java.util.Optional;

/**
 * JSON's {@code null}.
 *
 * <p><b>A value at this layer, and the void sentinel only at a typed position.</b> [TSON-JSON] §7
 * makes JSON null the void sentinel's spelling, but that is a rule about reading a value <em>against a
 * declaration</em> -- which a tree with no schema binding has none of, there being no Class 1 in
 * this encoding (§1.3 principle 1). Resolving it here would settle a schema's question in a layer that
 * has no schema, which is one of the two disagreements that make this a stack of its own.
 *
 * <p>{@link JsonValue#tryValue()} is empty for this and only this, which is the nearest a caller gets
 * to §7's reading without a position to read against.
 */
public record JsonNull() implements JsonValue {

    public static final JsonNull INSTANCE = new JsonNull();

    public static JsonNull of() {
        return INSTANCE;
    }

    @Override
    public Optional<JsonValue> tryValue() {
        return Optional.empty();
    }

    @Override
    public String toString() {
        return "null";
    }
}
