package io.ltr8.tson.base;

/**
 * A document that is not well-formed in the encoding it claims to be written in -- one fact, stated by
 * every encoding, so one exception rather than one per encoding.
 *
 * <p>From TSON text: structural mismatches -- unclosed brackets, adjacency violations, unexpected tokens,
 * missing separators, {@code !!} without an adjacent colon form, a directive name outside the closed
 * positional set or outside its placement ([TSON-DATA] §3.3). From JSON: everything
 * {@code JsonLexer} and {@code JsonStream} refuse -- malformed UTF-8, a bad token, a structure RFC 8259's
 * grammar does not admit, and the content [TSON-JSON] §3.1's profile refuses on top of it (a surrogate
 * escape with nothing to pair, a duplicate member name).
 *
 * <p><b>The two encodings divide it differently, and that is theirs to divide.</b> [TSON-DATA] §8.1 makes
 * a lexer error and a parse error separate categories, and TSON text keeps them separate in the type --
 * {@code LexException} beside this. [TSON-JSON] §9.4 splits the same ground across the same two categories
 * but the JSON stack raises one type for both, because the split there is the schema-directed layer's to
 * make when it classifies a failure into a {@link Diagnostic}; a second opinion carried on the exception
 * could only disagree with it. What is shared is the fact, never the categorising.
 *
 * <p><b>{@link #getMessage()} states what went wrong, never where.</b> The location is {@link #position()},
 * which is what a diagnostic carries into {@code dataPosition} -- a message repeating it makes every
 * renderer print the location twice, in two different formats, and hands a machine consumer parsing
 * {@code message} a second copy with no byte offset. {@link #toString()} appends it, so a stack trace still
 * says where without the diagnostic inheriting it.
 *
 * <p><b>The location is a {@link SourcePosition}</b>, and each encoding constructs it with its own record:
 * {@code Position} in {@code tson-compiler}, {@code JsonPosition} in {@code tson-json}, both of which
 * implement it. The three coordinates a report points at are all any consumer of one needs, so neither
 * record has to move here -- one interface, one record per encoding.
 * {@link LimitExceededException} carries its location the same way and for the same reason.
 *
 * <p><b>{@link #expected()}/{@link #actual()} are the machine-readable half of the same failure</b>,
 * carried so a diagnostic built from this exception has a structured account and not only a sentence --
 * the same division of labour {@code AtomTypeException} makes for value errors, and the reason
 * {@code expected} names the <em>construct</em> the position admits ({@code a type reference}, {@code a
 * record field's ':'}, {@code a member name is due}) rather than the token class that would have satisfied
 * it. Both are {@code ""} where a throw site states a rule rather than a substitution -- an adjacency
 * violation, a missing separator, malformed UTF-8 -- and nothing invents a pair to fill them.
 */
public final class ParseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient SourcePosition position;
    private final String expected;
    private final String actual;

    public ParseException(String message, SourcePosition position) {
        this(message, "", "", position);
    }

    public ParseException(String message, String expected, String actual, SourcePosition position) {
        super(message);
        this.position = position;
        this.expected = expected;
        this.actual = actual;
    }

    public SourcePosition position() {
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

    /**
     * The message plus its location -- what a stack trace prints, and the only place the two are joined.
     *
     * <p><b>Line and column, spelled here rather than delegated to the position.</b> Delegating would let
     * each encoding use its own reference vocabulary -- JSON says {@code position} where this says
     * {@code column}, matching JEP 540 -- but {@code Position}'s {@code toString()} is load-bearing
     * elsewhere and must stay the record default, so there is nothing to delegate to. A stack trace saying
     * {@code column} of a JSON document names the right number under a neighbouring format's word for it.
     */
    @Override
    public String toString() {
        return super.toString() + " at line " + position.line() + ", column " + position.column();
    }
}
