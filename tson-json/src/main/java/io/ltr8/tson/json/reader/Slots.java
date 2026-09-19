package io.ltr8.tson.json.reader;

/**
 * What a container's mode-free loop puts where a value cannot say what the document did -- read by the mode's
 * builder, which decides what each becomes. A tree keeps a placeholder; a bound object builds nothing where a
 * value was refused, and holds {@code null} where one was absent.
 */
final class Slots {

    /** Stated as absent -- JSON null where the position decodes to absence: seen, and holding no value. */
    static final Object ABSENT = marker("ABSENT");

    /** Null kept at {@code OPTIONAL_FIXED = _}, where presence is the information ([TSON-SCHEMA] §5.2). */
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
