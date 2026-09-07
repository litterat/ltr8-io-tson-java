package io.ltr8.tson.base;


/**
 * A document asked for more than this processor's {@link TsonLimitsPolicy} will spend -- [TSON-DATA] §9.1.
 *
 * <p><b>Not a parse error, and deliberately not a subclass of one.</b> {@code TsonParseException} says the
 * document is malformed, which is a verdict every processor reaching the same bytes would repeat; this says
 * only that <em>this</em> deployment declined, and another with a higher limit would read the same document
 * without complaint. Keeping the two apart in the type is what lets a facade route them to different {@link
 * Diagnostic.Code}s ({@link Diagnostic#ofLimitExceeded} against
 * {@code TsonDiagnostics.ofBaseSyntaxError}) rather than reporting a configured bound as a syntax
 * failure.
 *
 * <p><b>{@link #getMessage()} states what went wrong, never where</b> -- the same division {@code TsonParseException} makes, for the same reason: the location is {@link #position()}, and a message
 * repeating it makes every renderer print it twice.
 */
public final class TsonLimitExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient SourcePosition position;
    private final int limit;

    public TsonLimitExceededException(String message, int limit, SourcePosition position) {
        super(message);
        this.limit = limit;
        this.position = position;
    }

    /** Where the document crossed the limit -- the token that opened the container that did not fit. */
    public SourcePosition position() {
        return position;
    }

    /** The bound that was crossed, so a caller can say what to raise without re-reading the policy. */
    public int limit() {
        return limit;
    }

    /** The message plus its location -- what a stack trace prints, and the only place the two are joined. */
    @Override
    public String toString() {
        return super.toString() + " at line " + position.line() + ", column " + position.column();
    }
}
