package io.ltr8.tson.base.diagnostics;

import io.ltr8.tson.base.Diagnostic;

/**
 * What a bound class's refusal says -- its constructor, its bridge, or a collection's {@code put} throwing for
 * the values a read hands it -- stated once, for both encodings. A compact constructor's own check is the common
 * case; a collection that refuses {@code null} meeting a void value is another.
 *
 * <p><b>The code follows the contract the document is read against.</b> Under a schema the schema is the
 * contract, so a value it admits and the class refuses is the binding disagreeing with the schema:
 * {@code BIND_MISMATCH}, which is no verdict on the document. With no schema the class is the contract, and its
 * refusal is the document not fitting it: {@code TYPE_MISMATCH}, a verdict.
 */
public final class BindingDiagnostics {

    private BindingDiagnostics() {
    }

    /** What the class was handed: the noun a message names it by, and the one {@code expected} names it by. */
    public enum Handed {
        VALUE("the value", "a value"),
        ELEMENTS("the elements", "elements"),
        ENTRIES("the entries", "entries"),
        POSITIONS("the positions", "positions");

        private final String read;
        private final String accepted;

        Handed(String read, String accepted) {
            this.read = read;
            this.accepted = accepted;
        }
    }

    /** {@code type} refused what a schema-directed read handed it: the class and the schema disagree. */
    public static Refusal rejectedUnderSchema(Class<?> type, Handed handed, Throwable cause) {
        return new Refusal(Diagnostic.Code.BIND_MISMATCH,
                "%s rejected %s the schema admits: %s -- the bound class and the schema do not agree"
                        .formatted(type.getSimpleName(), handed.read, cause),
                handed.accepted + " " + type.getSimpleName() + " accepts", String.valueOf(cause.getMessage()));
    }

    /** {@code type} refused what a read directed by the class alone handed it: the document does not fit it. */
    public static Refusal rejectedByClass(Class<?> type, Handed handed, Throwable cause) {
        return new Refusal(Diagnostic.Code.TYPE_MISMATCH,
                "%s rejected %s read for it: %s".formatted(type.getSimpleName(), handed.read, cause),
                handed.accepted + " " + type.getSimpleName() + " accepts", String.valueOf(cause.getMessage()));
    }
}
