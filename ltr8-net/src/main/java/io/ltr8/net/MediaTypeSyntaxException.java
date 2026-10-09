package io.ltr8.net;

/** Text that is not a {@link MediaType}; the {@link #reason()} says what is wrong. */
public final class MediaTypeSyntaxException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final String text;
    private final String reason;

    public MediaTypeSyntaxException(String reason, String text) {
        super("'" + text + "' " + reason);
        this.text = text;
        this.reason = reason;
    }

    /** The text that failed to parse. */
    public String text() {
        return text;
    }

    /** What is wrong, as a predicate on the text: {@code "is a media range ..."}. */
    public String reason() {
        return reason;
    }
}
