package io.ltr8.tson.compiler;

/**
 * Where a read's problems go. This is the one decision separating a fail-fast read from a collecting one, and the
 * seam a caller implements to route diagnostics anywhere else -- a formatter writing each one to stdout as it
 * arrives, a collector capped at some budget, a telemetry sink.
 *
 * <p><b>A push sink, deliberately.</b> {@link TsonReadContext} builds each {@link Diagnostic} from the path and
 * positions it already tracks, then hands it here; this interface decides its fate and nothing else. That split is
 * what lets a receiver stream: a diagnostic is delivered the moment it is found, not after the whole read.
 *
 * <p><b>An implementation aborts the read by throwing</b> -- which is exactly how {@link #throwing()} itself works,
 * so there is one mechanism rather than a separate "stop" signal. A receiver that returns normally lets the read
 * continue, and the reader keeps a {@code null} placeholder for the failed field/element (see {@link
 * TsonReadContext}'s own note on placeholder-on-failure).
 *
 * <p>Lifecycle belongs to the caller: a receiver holding an open resource is flushed or closed after the read
 * returns, which is why this stays a single-method interface with no {@code close()} of its own.
 */
@FunctionalInterface
public interface TsonDiagnosticsReceiver {

    /** Accepts one problem found during a read. Throwing from here aborts the read. */
    void report(Diagnostic diagnostic);

    /**
     * Throws {@link TsonReadException} carrying the first diagnostic reported -- the default for every read that
     * doesn't name a receiver. Stateless, so one shared instance serves every read.
     */
    static TsonDiagnosticsReceiver throwing() {
        return ThrowingDiagnosticsReceiver.INSTANCE;
    }

    /** A fresh collector that accumulates every diagnostic and lets the read continue to the end. */
    static TsonDiagnosticsCollector collecting() {
        return new TsonDiagnosticsCollector();
    }
}
