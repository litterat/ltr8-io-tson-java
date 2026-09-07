package io.ltr8.tson.json;

/**
 * A JSON document asked for more than this processor will spend -- [TSON-JSON] §10.1, which is
 * [TSON-DATA] §9.1's limits policy in JSON clothing and applies with the same defaults.
 *
 * <p><b>Not a parse error, and deliberately not a subclass of one.</b> {@link JsonParseException} says
 * the document is not JSON, which is a verdict every processor reaching the same bytes would repeat;
 * this says only that <em>this</em> deployment declined, and another with a higher bound would read
 * the same document without complaint. §10.1 calls a refusal [TSON-DATA] §8.1's fifth outcome —
 * reported alongside the four categories and never one of them — so the two must be distinguishable
 * by type, or a configured bound reaches a consumer as a syntax failure.
 *
 * <p>{@code tson-compiler}'s {@code TsonLimitExceededException} is the same fact on the other
 * encoding, and the two are separate types because the stacks are separate. The <em>bound</em> is not
 * duplicated: §10.1 makes it one policy across both encodings, so the number arrives here as a
 * constructor argument rather than as a second policy object with its own default to drift.
 */
public final class JsonLimitExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient JsonPosition position;
    private final int limit;

    public JsonLimitExceededException(String message, int limit, JsonPosition position) {
        super(message);
        this.limit = limit;
        this.position = position;
    }

    /** Where the document crossed the bound -- the token that opened the container that did not fit. */
    public JsonPosition position() {
        return position;
    }

    /** The bound that was crossed, so a caller can say what to raise without re-reading the policy. */
    public int limit() {
        return limit;
    }

    /** The message plus its location -- what a stack trace prints, and the only place the two are joined. */
    @Override
    public String toString() {
        return super.toString() + " at " + position;
    }
}
