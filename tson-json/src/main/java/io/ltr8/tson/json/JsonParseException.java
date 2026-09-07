package io.ltr8.tson.json;

/**
 * A JSON document that is not one: malformed UTF-8, a bad token, a structure the grammar does not
 * admit, or content [TSON-JSON] §3.1's profile refuses (a surrogate escape with nothing to pair, a
 * duplicate member name).
 *
 * <p>Unchecked, and named for JEP 540's exception of the same name, which it stands in for while
 * {@code jdk.incubator.json} is unavailable. §9.4 splits what this covers across two of [TSON-DATA]
 * §8.1's categories -- a lexer error for malformed UTF-8 and ill-formed strings, a parse error for
 * grammar violations -- and the split is the schema-directed layer's to make when it classifies one
 * of these into a {@code Diagnostic}; carrying it here as well would be a second opinion about one
 * fact.
 *
 * <p><b>{@link #getMessage()} states what went wrong, never where.</b> {@link #position()} is the
 * location, so a diagnostic built from this carries it structurally rather than by parsing prose --
 * the same rule {@code LexException} and {@code TsonParseException} follow. {@link #toString()}
 * appends it, for a stack trace.
 */
public final class JsonParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient JsonPosition position;

    public JsonParseException(String message, JsonPosition position) {
        super(message);
        this.position = position;
    }

    public JsonPosition position() {
        return position;
    }

    /** The message plus its location -- what a stack trace prints, and the only place the two are joined. */
    @Override
    public String toString() {
        return super.toString() + " at " + position;
    }
}
