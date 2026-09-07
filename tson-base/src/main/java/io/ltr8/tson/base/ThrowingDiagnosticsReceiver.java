package io.ltr8.tson.base;

/**
 * The fail-fast {@link DiagnosticsReceiver}: the first reported problem becomes a {@link ReadException},
 * so a read stops at it. Stateless, hence the single shared {@link #INSTANCE} -- a caller reaches it through
 * {@link DiagnosticsReceiver#throwing()}, never by name.
 */
final class ThrowingDiagnosticsReceiver implements DiagnosticsReceiver {

    static final ThrowingDiagnosticsReceiver INSTANCE = new ThrowingDiagnosticsReceiver();

    private ThrowingDiagnosticsReceiver() {
    }

    @Override
    public void report(Diagnostic diagnostic) {
        throw new ReadException(diagnostic);
    }
}
