package io.ltr8.tson.compiler;

/**
 * A failure while writing a Java object to TSON text via {@link TsonObjectWriter} -- e.g. a value
 * whose type the writer has no way to emit, or a bridge that fails to unwrap it. The unchecked
 * counterpart to {@link TsonReadException} on the read side, so the whole read/write object-binding
 * stack throws only unchecked exceptions; the underlying {@code tson-bind} {@code DataBindException}
 * is preserved as the cause.
 */
public final class TsonWriteException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TsonWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
