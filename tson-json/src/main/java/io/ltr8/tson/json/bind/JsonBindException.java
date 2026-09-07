package io.ltr8.tson.json.bind;

import io.ltr8.tson.json.JsonPosition;

/**
 * A JSON document and the class it was read into disagree: a value of the wrong shape for the
 * component it lands in, a member the class requires and the document omits, a number the target type
 * cannot hold exactly, a member name stated twice.
 *
 * <p><b>Not a {@code JsonParseException}.</b> The document is well-formed JSON -- it parsed to get
 * here. What failed is the reading of it <em>at a typed position</em>, where the type is the target
 * class's own descriptor rather than a TSON schema's. Keeping the two apart in the type is what lets a
 * caller tell "this is not JSON" from "this is not my JSON".
 *
 * <p>{@link #getMessage()} states what went wrong and {@link #position()} where -- the same division
 * every exception in this module makes. The position is the value that could not be read, or the
 * object that lacked a member.
 */
public final class JsonBindException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient JsonPosition position;

    public JsonBindException(String message, JsonPosition position) {
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
