package io.ltr8.tson.compiler;

/**
 * A Class 2 (schema-validated) reading failure -- e.g. a required field missing from a record. Thrown by
 * {@link TsonDiagnosticsReceiver#throwing()} the instant {@link TsonReadContext#report} hands it a problem;
 * the identical information is what {@link TsonDiagnosticsCollector} instead accumulates without throwing,
 * so every receiver reports through exactly one shape, {@link Diagnostic}, whichever a caller chose.
 */
public final class TsonReadException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Diagnostic diagnostic;

    public TsonReadException(Diagnostic diagnostic) {
        super(diagnostic.message());
        this.diagnostic = diagnostic;
    }

    public Diagnostic diagnostic() {
        return diagnostic;
    }
}
