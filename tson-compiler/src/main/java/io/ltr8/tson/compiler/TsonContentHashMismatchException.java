package io.ltr8.tson.compiler;

/**
 * A hash-pinned reference's content did not match its declared {@code ?sha256=} digest ([TSON-DATA]
 * §2.2.1) -- an integrity failure, never a fallback. Thrown by {@link TsonContentHash#verify} while
 * resolving a pinned {@code !!schema}/{@code !!import}/{@code !!meta}.
 */
public final class TsonContentHashMismatchException extends RuntimeException {

    public TsonContentHashMismatchException(String message) {
        super(message);
    }
}
