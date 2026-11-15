package io.ltr8.tson.compiler.reader;

/**
 * The scope a foreign value is read in ([TSON-SCHEMA] §7.9): the foreign schema whose namespace its names resolve in,
 * and the extern it was read through, with the schema declaring that extern, which is the enclosing scope's. A
 * diagnostic raised inside the value names both.
 */
public record ForeignScope(String extern, String declaredIn, String schema) {

    /** The extern as a message names it: its name and the schema that declares it. */
    public String through() {
        return "'" + extern + "' of " + declaredIn;
    }
}
