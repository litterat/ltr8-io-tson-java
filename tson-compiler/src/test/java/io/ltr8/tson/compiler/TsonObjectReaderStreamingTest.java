package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.stream.TsonEvent;
import io.ltr8.tson.compiler.stream.TsonEventSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two capabilities the streaming, ctx-based {@link TsonObjectReader} rewrite unlocks over the
 * old tree-based binder: genuine laziness (a fail-fast error never pulls the rest of a large
 * document), and collecting-mode multi-error binding (every independent problem in one pass, not
 * just the first). The ordinary success/behaviour matrix is covered by {@code TsonObjectReaderTest}.
 */
class TsonObjectReaderStreamingTest {

    public record Holder(long a, long b, List<Long> huge) {
    }

    public record TwoFields(long first, long second) {
    }

    /** Counts every event actually pulled via {@link #next()}; {@link #peek()} doesn't advance, so it doesn't count. */
    private static final class CountingEventSource implements TsonEventSource {
        private final TsonEventSource delegate;
        private int pulled = 0;

        CountingEventSource(TsonEventSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean hasNext() {
            return delegate.hasNext();
        }

        @Override
        public TsonEvent next() {
            pulled++;
            return delegate.next();
        }

        @Override
        public TsonEvent peek() {
            return delegate.peek();
        }
    }

    @Test
    void aFailFastErrorOnAnEarlyFieldNeverPullsAHugeTrailingFieldOffTheStream() {
        // "b" is declared long but the data puts an array there -- a fail-fast bind must throw right
        // there, well before "huge"'s own 50,000 elements are ever reached.
        StringBuilder hugeArray = new StringBuilder("[");
        hugeArray.append("1 ".repeat(50_000));
        hugeArray.append("]");
        String source = "{ a: 1  b: [1 2 3]  huge: " + hugeArray + " }";

        TsonDataStream realStream = new TsonDataStream(source);
        realStream.next(); // DocumentStart
        CountingEventSource counting = new CountingEventSource(realStream);
        TsonReadContext ctx = TsonReadContext.throwing(counting);

        assertThrows(TsonReadException.class, () -> new TsonObjectReader().read(ctx, Holder.class));

        assertTrue(counting.pulled < 100, "pulled " + counting.pulled + " events, expected well under 100");
    }

    @Test
    void collectingModeReportsEveryIndependentBindingProblemInOnePass() {
        // Both fields are malformed independently: "first" is an array where a long is wanted, and
        // "second" is likewise. A collecting bind surfaces both, not just the first.
        String source = "{ first: [1]  second: [2] }";
        TsonDataStream stream = new TsonDataStream(source);
        stream.next(); // DocumentStart
        TsonDiagnosticsCollector problems = new TsonDiagnosticsCollector();
        TsonReadContext ctx = TsonReadContext.of(stream, problems);

        TwoFields result = new TsonObjectReader().read(ctx, TwoFields.class);

        assertEquals(2, problems.diagnostics().size(), problems.diagnostics().toString());
        assertEquals("/first", problems.diagnostics().get(0).path());
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, problems.diagnostics().get(0).code());
        assertEquals("/second", problems.diagnostics().get(1).path());
        // A constructor can't take nulls for its primitive long parameters, so no object is built --
        // the caller already has both problems from the collector.
        assertNull(result);
    }
}
