package io.ltr8.tson.base;

/**
 * A {@link DiagnosticsReceiver} that passes every problem on and remembers whether there was one -- what a read
 * facade wraps its caller's receiver in for one whole-document read.
 *
 * <p><b>A read that reported anything returns no value</b>, in every mode and both encodings: a tree's
 * placeholder and a real absent value are the same node, so a partial result cannot say which of its parts to
 * trust, and the diagnostics -- each with a path into the document the caller already holds -- are the answer.
 * Counting at the receiver rather than asking the read context catches every route a problem takes, a token
 * refusal the stream reports directly among them.
 *
 * <p>Single-read, like {@link DiagnosticsCollector}: take a fresh one per read.
 */
public final class CountingReceiver implements DiagnosticsReceiver {

    private final DiagnosticsReceiver delegate;
    private boolean reported;

    public CountingReceiver(DiagnosticsReceiver delegate) {
        this.delegate = delegate;
    }

    @Override
    public void report(Diagnostic diagnostic) {
        reported = true;
        delegate.report(diagnostic);
    }

    /** Whether anything has been reported through this receiver. */
    public boolean reported() {
        return reported;
    }
}
