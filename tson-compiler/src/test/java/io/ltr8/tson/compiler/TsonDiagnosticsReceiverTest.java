package io.ltr8.tson.compiler;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TsonDiagnosticsReceiver} -- the seam deciding where a read's problems go. The two built-ins are
 * covered here alongside a caller-written one, since the point of the interface is that a third
 * destination (a formatter, a budget, a telemetry sink) needs no support from the reader stack.
 */
class TsonDiagnosticsReceiverTest {

    /** Three fields, each an array where a long is wanted -- three independent problems in document order. */
    public record Three(long a, long b, long c) {
    }

    private static final String THREE_BAD = "{ a: [1]  b: [2]  c: [3] }";

    @Test
    void theThrowingReceiverStopsAtTheFirstProblem() {
        TsonReadException thrown = assertThrows(TsonReadException.class,
                () -> new TsonObjectReader().read(THREE_BAD, Three.class));

        assertEquals(Optional.of("/a"), thrown.diagnostic().path());
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, thrown.diagnostic().code());
    }

    @Test
    void aCollectorGathersEveryProblemInDocumentOrder() {
        TsonDiagnosticsCollector problems = TsonDiagnosticsReceiver.collecting();

        new TsonObjectReader().withDiagnostics(problems).read(THREE_BAD, Three.class);

        assertEquals(List.of("/a", "/b", "/c"), problems.diagnostics().stream().map(d -> d.path().orElseThrow()).toList());
    }

    @Test
    void aCollectorIsEmptyWhenNothingIsWrong() {
        TsonDiagnosticsCollector problems = TsonDiagnosticsReceiver.collecting();

        Three value = new TsonObjectReader().withDiagnostics(problems).read("{ a: 1  b: 2  c: 3 }", Three.class);

        assertEquals(new Three(1, 2, 3), value);
        assertTrue(problems.isEmpty());
    }

    @Test
    void aReceiverIsHandedEachProblemAsItIsFoundNotInOneBatchAtTheEnd() {
        // A budget of two: throwing from report aborts the read, which is only possible if diagnostics
        // arrive during it. If they were batched at the end, all three would already have been found.
        List<String> seen = new ArrayList<>();
        TsonDiagnosticsReceiver cappedAtTwo = diagnostic -> {
            seen.add(diagnostic.path().orElseThrow());
            if (seen.size() == 2) {
                throw new IllegalStateException("enough");
            }
        };

        assertThrows(IllegalStateException.class,
                () -> new TsonObjectReader().withDiagnostics(cappedAtTwo).read(THREE_BAD, Three.class));

        assertEquals(List.of("/a", "/b"), seen);
    }

    @Test
    void withDiagnosticsLeavesTheReaderItWasDerivedFromUnchanged() {
        TsonObjectReader reader = new TsonObjectReader();

        reader.withDiagnostics(TsonDiagnosticsReceiver.collecting()).read(THREE_BAD, Three.class);

        assertThrows(TsonReadException.class, () -> reader.read(THREE_BAD, Three.class));
    }
}
