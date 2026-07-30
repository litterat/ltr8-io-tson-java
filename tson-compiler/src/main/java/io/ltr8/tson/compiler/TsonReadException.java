package io.ltr8.tson.compiler;

/**
 * A Class 2 (schema-validated) reading failure -- e.g. a required field missing from a record.
 * Thrown by a fail-fast {@link TsonReadContext} the instant {@link TsonReadContext#report} is
 * called; the identical information a collecting context instead accumulates into its own {@link
 * TsonReadContext#diagnostics()} list without throwing, so both modes report through exactly one
 * shape, {@link Diagnostic}, regardless of which a caller chose.
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
