package io.ltr8.tson.json.reader;

/**
 * What a container's mode-free loop puts where a value cannot say what the document did -- read by the mode's
 * builder, which decides what each becomes. Neither mode builds anything where a value was refused; an absent
 * one becomes {@code JsonNull} in a tree and {@code null} in a bound object.
 */
final class Slots {

    /** Stated as absent -- JSON null where the position decodes to absence: seen, and holding no value. */
    static final Object ABSENT = marker("ABSENT");

    /** Null kept at a field pinned to absent ({@code = _}), written or injected ([TSON-SCHEMA] §5.2). */
    static final Object NULL_KEPT = marker("NULL_KEPT");

    /** A child that refused its value. */
    static final Object REFUSED = marker("REFUSED");

    private Slots() {
    }

    private static Object marker(String name) {
        return new Object() {
            @Override
            public String toString() {
                return name;
            }
        };
    }
}
