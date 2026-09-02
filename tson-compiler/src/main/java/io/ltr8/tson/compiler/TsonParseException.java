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
 *
 * <p><b>{@link #expected()}/{@link #actual()} are the machine-readable half of the same failure</b>,
 * carried so a diagnostic built from this exception has a structured account and not only a
 * sentence -- the same division of labour {@code AtomTypeException} makes for value errors, and the
 * reason {@code expected} names the <em>construct</em> the position admits ({@code a type
 * reference}, {@code a record field's ':'}) rather than the token class that would have satisfied
 * it. Both are {@code ""} where a throw site states a rule rather than a substitution -- an
 * adjacency violation, a missing separator -- and nothing invents a pair to fill them.
 */
public final class TsonParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Position position;
    private final String expected;
    private final String actual;

    public TsonParseException(String message, Position position) {
        this(message, "", "", position);
    }

    public TsonParseException(String message, String expected, String actual, Position position) {
        super(message);
        this.position = position;
        this.expected = expected;
        this.actual = actual;
    }

    public Position position() {
        return position;
    }

    /** The construct admissible where the parse failed, or {@code ""} where the failure isn't a substitution. */
    public String expected() {
        return expected;
    }

    /** What was written there instead, or {@code ""} alongside an empty {@link #expected()}. */
    public String actual() {
        return actual;
    }

    /** The message plus its location -- what a stack trace prints, and the only place the two are joined. */
    @Override
    public String toString() {
        return super.toString() + " at line " + position.line() + ", column " + position.column();
    }
}
