package io.ltr8.tson.cli;

import io.ltr8.tson.compiler.TsonUnicodePolicy;
import io.ltr8.tson.compiler.TsonDataStream;
import io.ltr8.tson.compiler.TsonDiagnosticsReceiver;
import io.ltr8.tson.compiler.TsonReadContext;

/**
 * Positions a {@link TsonReadContext} at a whole document's root value, so a test can drive one compiled
 * {@code TsonTypeReader} in isolation rather than through {@code TsonTreeReader}/{@code TsonObjectReader}.
 *
 * <p><b>A test affordance, not a missing library method</b> -- document reading is the two facades' job.
 * See {@code io.ltr8.tson.compiler.TestDocuments}, of which this is the peer for this module's own tests
 * (no test-fixtures wiring in this build, so the few lines are repeated rather than shared).
 */
final class TestDocuments {

    private TestDocuments() {
    }

    /** Fail-fast, over a whole document's own source text -- the cursor is left on the root value's first event. */
    static TsonReadContext document(String source) {
        return document(source, TsonDiagnosticsReceiver.throwing());
    }

    /** As {@link #document(String)}, reporting through {@code receiver} instead of throwing at the first problem. */
    static TsonReadContext document(String source, TsonDiagnosticsReceiver receiver) {
        TsonDataStream stream = new TsonDataStream(source);
        TsonReadContext ctx = TsonReadContext.of(stream, receiver, TsonUnicodePolicy.unrestricted());
        ctx.next(); // DocumentStart
        return ctx;
    }
}
