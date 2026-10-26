package io.ltr8.net;

/**
 * Text that is not a host: not a {@link HostName}, or for {@link Host}, not a host name or an IP address. The
 * {@link #reason()} says what is wrong and, where there is one, what to write instead.
 */
public final class HostSyntaxException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final String text;
    private final String reason;

    public HostSyntaxException(String reason, String text) {
        super("'" + text + "' " + reason);
        this.text = text;
        this.reason = reason;
    }

    /** The text that failed to parse. */
    public String text() {
        return text;
    }

    /** What is wrong, as a predicate on the text: {@code "ends with a dot -- ..."}. */
    public String reason() {
        return reason;
    }
}
