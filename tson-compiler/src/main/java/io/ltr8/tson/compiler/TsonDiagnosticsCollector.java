package io.ltr8.tson.compiler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A {@link TsonDiagnosticsReceiver} that accumulates every problem instead of throwing, so a read runs to the end
 * and returns a partial value alongside the full list of what was wrong with it. The value-plus-diagnostics shape
 * a validate-and-retry loop wants.
 *
 * <p>Stateful and single-read: {@link #diagnostics()} reports what this instance has been given, so reusing one
 * across two reads accumulates both. Take a fresh one per read -- {@link TsonDiagnosticsReceiver#collecting()}.
 */
public final class TsonDiagnosticsCollector implements TsonDiagnosticsReceiver {

    private final List<Diagnostic> diagnostics = new ArrayList<>();

    /** A collector holding nothing yet -- equivalent to {@link TsonDiagnosticsReceiver#collecting()}. */
    public TsonDiagnosticsCollector() {
    }

    @Override
    public void report(Diagnostic diagnostic) {
        diagnostics.add(diagnostic);
    }

    /** Every diagnostic reported so far, in the order the read found them. */
    public List<Diagnostic> diagnostics() {
        return Collections.unmodifiableList(diagnostics);
    }

    /** Whether nothing has been reported -- {@code true} means the read found no problems. */
    public boolean isEmpty() {
        return diagnostics.isEmpty();
    }
}
