package io.ltr8.tson;

/**
 * A schema reference that could not be turned into schema text, and why.
 *
 * <p><b>The pipeline's three-way classification has no slot for this.</b> A failure inside this library is
 * either the author's schema being wrong ({@code TsonSchemaValidationException}), a construct not implemented
 * yet ({@code UnsupportedOperationException}), or a broken invariant ({@code IllegalStateException}). A schema
 * host that is down is none of the three: nobody's document is wrong, nothing is unimplemented, and no
 * invariant broke. Fetching brings its own failure modes, so it brings its own exception.
 *
 * <p><b>{@link Reason} is the part worth acting on</b>, and the reason this is not just a message. It
 * separates a caller's mistake from an operator's: {@code NOT_PERMITTED} means the reference names something
 * this deployment will not load and no retry will help, where {@code TIMEOUT} and {@code TRANSPORT} say the
 * reference was acceptable and the world was not. A server mapping schema failures onto status codes needs
 * that split, and cannot recover it from a flattened message.
 */
public final class TsonSchemaFetchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why a fetch failed. */
    public enum Reason {

        /** Policy refused it: not an allowed host, not a legal identity, or no content-hash pin where one is required. */
        NOT_PERMITTED,

        /** The location was reached and does not have it. */
        NOT_FOUND,

        /** The location could not be reached, or answered with something other than a document. */
        TRANSPORT,

        /** The location did not answer in time. */
        TIMEOUT,

        /** The location answered with more bytes than a schema document is allowed to be. */
        TOO_LARGE
    }

    private final String uri;
    private final Reason reason;

    public TsonSchemaFetchException(String uri, Reason reason, String message, Throwable cause) {
        super("cannot fetch schema '" + uri + "': " + message, cause);
        this.uri = uri;
        this.reason = reason;
    }

    /** The schema reference that could not be fetched, as written. */
    public String uri() {
        return uri;
    }

    /** Why it could not be. */
    public Reason reason() {
        return reason;
    }
}
