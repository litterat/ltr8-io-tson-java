package io.ltr8.tson.compiler;

/**
 * The fail-fast {@link TsonDiagnosticsReceiver}: the first reported problem becomes a {@link TsonReadException},
 * so a read stops at it. Stateless, hence the single shared {@link #INSTANCE} -- a caller reaches it through
 * {@link TsonDiagnosticsReceiver#throwing()}, never by name.
 */
final class ThrowingDiagnosticsReceiver implements TsonDiagnosticsReceiver {

    static final ThrowingDiagnosticsReceiver INSTANCE = new ThrowingDiagnosticsReceiver();

    private ThrowingDiagnosticsReceiver() {
    }

    @Override
    public void report(Diagnostic diagnostic) {
        throw new TsonReadException(diagnostic);
    }
}
