package io.ltr8.tson.json.tree;

import java.util.Optional;

/**
 * A JSON string, as its decoded content -- escapes resolved, a surrogate pair already one character.
 * The value set is Unicode scalar sequences ([TSON-JSON] §3.1), which is exactly {@code text}'s (§5.6).
 */
public record JsonString(String value) implements JsonValue {

    public JsonString {
        if (value == null) {
            throw new NullPointerException("a JSON string has content, possibly empty, and never null");
        }
    }

    public static JsonString of(String value) {
        return new JsonString(value);
    }

    @Override
    public String asString() {
        return value;
    }

    @Override
    public Optional<JsonValue> tryValue() {
        return Optional.of(this);
    }

    @Override
    public String toString() {
        return JsonText.quote(value);
    }
}
