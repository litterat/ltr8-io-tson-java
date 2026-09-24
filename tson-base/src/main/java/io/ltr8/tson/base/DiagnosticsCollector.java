package io.ltr8.tson.base;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A {@link DiagnosticsReceiver} that accumulates every problem instead of throwing, so a read runs to the end
 * and every problem in the document is found in one pass. A read that reported anything returns no value
 * ({@link CountingReceiver}): the list, each entry with a path into the document, is the answer a
 * validate-and-retry loop works from.
 *
 * <p>Stateful and single-read: {@link #diagnostics()} reports what this instance has been given, so reusing one
 * across two reads accumulates both. Take a fresh one per read -- {@link DiagnosticsReceiver#collecting()}.
 */
public final class DiagnosticsCollector implements DiagnosticsReceiver {

    private final List<Diagnostic> diagnostics = new ArrayList<>();

    /** A collector holding nothing yet -- equivalent to {@link DiagnosticsReceiver#collecting()}. */
    public DiagnosticsCollector() {
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
