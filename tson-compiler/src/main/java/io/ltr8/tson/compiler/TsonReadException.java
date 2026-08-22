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

    /**
     * Appends where in the data the problem is, when the diagnostic locates it -- so a stack trace says
     * where without {@link #getMessage()} inheriting it, the same division {@code TsonParseException} makes
     * for a base-syntax failure. That matters most for the failures that <em>are</em> base-syntax ones: they
     * reach a fail-fast caller through this type now, and a trace that stopped saying where would be the one
     * thing lost in the trip through the receiver.
     */
    @Override
    public String toString() {
        return diagnostic.dataPosition()
                .map(p -> super.toString() + " at line " + p.line() + ", column " + p.column())
                .orElseGet(super::toString);
    }
}
