package io.ltr8.net;

/**
 * Thrown when text is not a URI-reference (RFC 3986) or an IRI-reference (RFC 3987) under the grammar asked for.
 * {@link #reason()} says what is wrong without quoting the text, so a caller can lead with its own subject;
 * {@link #index()} is the {@code char} index into {@link #text()} where the recognizer stopped.
 */
public final class IriSyntaxException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final String text;
    private final int index;
    private final String reason;

    public IriSyntaxException(String reason, String text, int index) {
        super("'" + text + "' " + reason);
        this.text = text;
        this.index = index;
        this.reason = reason;
    }

    /** The text that failed to parse. */
    public String text() {
        return text;
    }

    /** The {@code char} index into {@link #text()} where parsing failed. */
    public int index() {
        return index;
    }

    /** What is wrong, as a predicate on the text: {@code "has '[' at index 7, ..."}. */
    public String reason() {
        return reason;
    }
}
