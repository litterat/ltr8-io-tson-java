package io.ltr8.tson.compiler;

/**
 * Positions a {@link TsonReadContext} at a whole document's root value, so a unit test can drive one
 * compiled {@link TsonTypeReader} in isolation rather than through {@link TsonTreeReader}/{@link
 * TsonObjectReader}.
 *
 * <p><b>A test affordance, not a missing library method.</b> Document reading is the two facades' job --
 * they own the {@code !!schema} decision, the target-class check, and the pull past the root value that
 * makes a lazy {@link TsonDataStream} reject trailing content. This opens the frame and nothing else, which
 * is all a reader-level unit test needs; a test that cares about whole-document behaviour should go through
 * a facade, where that behaviour actually lives.
 */
public final class TestDocuments {

    private TestDocuments() {
    }

    /** Fail-fast, over a whole document's own source text -- the cursor is left on the root value's first event. */
    public static TsonReadContext document(String source) {
        return document(source, TsonDiagnosticsReceiver.throwing());
    }

    /** As {@link #document(String)}, reporting through {@code receiver} instead of throwing at the first problem. */
    public static TsonReadContext document(String source, TsonDiagnosticsReceiver receiver) {
        TsonDataStream stream = new TsonDataStream(source);
        TsonReadContext ctx = TsonReadContext.of(stream, receiver, TsonUnicodePolicy.unrestricted());
        ctx.next(); // DocumentStart
        return ctx;
    }
}
