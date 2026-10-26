package io.ltr8.tson.base;

/**
 * Text that names no canonical identity ([TSON-DATA] §2.2.1): not an IRI-reference, or one whose form an identity
 * refuses -- a fragment, userinfo, a port, a dot-segment, a host that is not a host name or an IP address, a path
 * with no IRI form or not in NFC, a query holding anything but a hash parameter. The {@link #reason()} says what is
 * wrong. Thrown by {@link Identity}'s readings; {@link CanonicalIdentity}'s methods, which read a schema's own
 * references, report the same text as a {@link SchemaValidationException}.
 */
public final class IdentitySyntaxException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final String text;
    private final String reason;

    public IdentitySyntaxException(String reason, String text) {
        super("'" + text + "' " + reason);
        this.text = text;
        this.reason = reason;
    }

    /** The text that names no identity. */
    public String text() {
        return text;
    }

    /** What is wrong, as a predicate on the text: {@code "carries a port, not permitted in an identifying URI"}. */
    public String reason() {
        return reason;
    }
}
