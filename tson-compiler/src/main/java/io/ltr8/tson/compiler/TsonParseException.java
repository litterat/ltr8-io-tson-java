package io.ltr8.tson.compiler;


/**
 * A compiler error (§8.1): structural mismatches -- unclosed brackets, adjacency violations,
 * unexpected tokens, missing separators, {@code !!} without an adjacent colon form, or a
 * directive name outside the closed positional set or outside its placement (§3.3).
 *
 * <p><b>{@link #getMessage()} states what went wrong, never where.</b> The location is {@link
 * #position()}, which is what {@link Diagnostic#ofBaseSyntaxError} carries into {@code dataPosition}
 * -- a message repeating it makes every renderer print the location twice, in two different
 * formats, and hands a machine consumer parsing {@code message} a second copy with no byte offset.
 * {@link #toString()} appends it, so a stack trace still says where without the diagnostic
 * inheriting it.
 */
public final class TsonParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Position position;

    public TsonParseException(String message, Position position) {
        super(message);
        this.position = position;
    }

    public Position position() {
        return position;
    }

    /** The message plus its location -- what a stack trace prints, and the only place the two are joined. */
    @Override
    public String toString() {
        return super.toString() + " at line " + position.line() + ", column " + position.column();
    }
}
