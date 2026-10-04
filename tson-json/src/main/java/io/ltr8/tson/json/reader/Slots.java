package io.ltr8.tson.json.reader;

/**
 * What a container's mode-free loop puts where a value cannot say what the document did -- read by the mode's
 * builder, which decides what each becomes. Neither mode builds anything where a value was refused; a void
 * one becomes {@code JsonNull} in a tree and {@code null} in a bound object.
 */
final class Slots {

    /** Stated void -- JSON null where the position is voidable: seen, and holding no value. */
    static final Object VOID = marker("VOID");

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
