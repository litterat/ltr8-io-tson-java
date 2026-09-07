package io.ltr8.tson.json;

/** {@code true} or {@code false}. */
public record JsonBoolean(boolean value) implements JsonValue {

    public static final JsonBoolean TRUE = new JsonBoolean(true);
    public static final JsonBoolean FALSE = new JsonBoolean(false);

    public static JsonBoolean of(boolean value) {
        return value ? TRUE : FALSE;
    }

    @Override
    public boolean asBoolean() {
        return value;
    }

    @Override
    public String toString() {
        return Boolean.toString(value);
    }
}
