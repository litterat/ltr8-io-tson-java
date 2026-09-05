package io.ltr8.tson.compiler;

import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [TSON-DATA] §9.1's nesting-depth limit -- {@link TsonLimitsPolicy}, the bound, and what a document past it
 * is told.
 *
 * <p><b>What this is really guarding is that the failure is a diagnostic at all.</b> The token stream is
 * iterative, but every reader over it descends by recursion ({@code SchemalessTreeReader.readNode} calling
 * {@code readArray} calling {@code readNode}), so before the limit existed a document a few thousand
 * containers deep exhausted the Java stack. A {@link StackOverflowError} is an {@link Error}: it passes
 * through every {@code catch (RuntimeException)} in the reader stack and in the CLI alike, so the one
 * outcome that case must never get -- a verdict on the document -- was the one it got.
 */
class LimitsPolicyTest {

    /** {@code n} nested arrays around a single value, the cheapest shape that nests at all. */
    private static String nested(int depth) {
        return "[".repeat(depth) + "1" + "]".repeat(depth);
    }

    private static List<Diagnostic> problems(TsonTreeReader reader, String document) {
        TsonDiagnosticsCollector collected = new TsonDiagnosticsCollector();
        reader.withDiagnostics(collected).read(document);
        return collected.diagnostics();
    }

    /**
     * The depth that used to reach a {@link StackOverflowError}, from a document of about 10 KB -- small
     * enough to arrive as an ordinary request body, which is what made this reachable rather than exotic.
     * Well past {@link TsonLimitsPolicy#DEFAULT_MAX_DEPTH}, so it is refused by the default alone.
     */
    private static final int PAST_THE_STACK = 6000;

    @Test
    void aDocumentDeepEnoughToExhaustTheStackIsReportedRatherThanThrown() {
        List<Diagnostic> problems = problems(new TsonTreeReader(), nested(PAST_THE_STACK));

        assertEquals(1, problems.size(), problems::toString);
        assertEquals(Diagnostic.Code.LIMIT_EXCEEDED, problems.getFirst().code());
    }

    /**
     * The bind path recurses the same way and through the same stream, so it gets the same answer -- the one
     * asymmetry between the modes ({@code ConstructionGuard}'s all-or-nothing) is about what a read that
     * reported keeps, not about whether it reports.
     */
    @Test
    void theObjectReaderIsBoundedTheSameWay() {
        TsonDiagnosticsCollector collected = new TsonDiagnosticsCollector();
        Object bound = new TsonObjectReader().withDiagnostics(collected)
                .read(nested(PAST_THE_STACK), Object.class);

        assertNull(bound);
        // Among rather than equal to: binding a bare nested array to Object reports its own mismatch first,
        // which is about the target class and not about the bound this is asking after.
        assertTrue(collected.diagnostics().stream().anyMatch(d -> d.code() == Diagnostic.Code.LIMIT_EXCEEDED),
                collected.diagnostics()::toString);
    }

    /** A schema document is untrusted input too, and reaches the bound through the same shared stream. */
    @Test
    void aSchemaDocumentIsBoundedTheSameWay() {
        String schema = "!!meta:\"https://tson.io/2026/35/m/meta.tn\"\n{ deep => " + nested(PAST_THE_STACK) + " }";

        assertThrows(TsonLimitExceededException.class,
                () -> new TsonSchemaParser(schema).parseSchemaDocument());
    }

    /** Exactly at the bound reads; one past it does not. The document either side differs by one bracket. */
    @Test
    void theBoundIsTheDeepestDepthThatStillReads() {
        TsonTreeReader reader = new TsonTreeReader().withLimits(new TsonLimitsPolicy(8));

        assertNotNull(reader.read(nested(8)));
        assertEquals(List.of(Diagnostic.Code.LIMIT_EXCEEDED),
                problems(reader, nested(9)).stream().map(Diagnostic::code).toList());
    }

    /**
     * A refusal names the bound at both ends of {@code expected}/{@code actual} and carries a position, so a
     * sender is told what to change without reading the message. The position is the token that opened the
     * container that did not fit.
     */
    @Test
    void aRefusalCarriesTheBoundAndWhereItWasCrossed() {
        Diagnostic refusal = problems(new TsonTreeReader().withLimits(new TsonLimitsPolicy(4)), nested(5))
                .getFirst();

        assertEquals("at most 4 levels of nesting", refusal.expected());
        assertEquals("more than 4", refusal.actual());
        assertTrue(refusal.dataPosition().isPresent(), refusal::toString);
    }

    /**
     * <b>The point of the whole exercise.</b> The document may be well-formed and valid; what happened is
     * that this deployment declined to spend the resources, so nothing about the document is being asserted.
     * Every consumer that routes on "was this checked" -- the CLI's {@code Outcome}, an HTTP surface's status
     * -- reads this and not the message.
     */
    @Test
    void aLimitRefusalIsNotAVerdictOnTheDocument() {
        assertFalse(Diagnostic.Code.LIMIT_EXCEEDED.verdict());
    }

    /** Fail-fast is the other half of the same routing: a receiver that throws still throws for this. */
    @Test
    void aFailFastReadThrowsRatherThanReturningNothing() {
        assertThrows(RuntimeException.class, () -> new TsonTreeReader().read(nested(PAST_THE_STACK)));
    }

    /**
     * Derivation leaves the original alone, the way {@code withTokenPolicy} does -- two readers off one
     * configuration must be able to disagree about the bound, which is what makes {@code limitsPolicy()}
     * worth reading off the reader that judged.
     */
    @Test
    void withLimitsDerivesRatherThanMutating() {
        TsonTreeReader base = new TsonTreeReader();
        TsonTreeReader deeper = base.withLimits(new TsonLimitsPolicy(500));

        assertEquals(TsonLimitsPolicy.DEFAULT_MAX_DEPTH, base.limitsPolicy().maxDepth());
        assertEquals(500, deeper.limitsPolicy().maxDepth());
        assertNotNull(deeper.read(nested(500)));
    }

    /**
     * A depth below one admits no document at all, so it is refused where it is stated rather than at the
     * first document it silently rejects.
     */
    @Test
    void aDepthBelowOneIsRefusedAtTheConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new TsonLimitsPolicy(0));
    }

    /** A document nowhere near the bound is unaffected, which is every real document. */
    @Test
    void anOrdinaryDocumentIsUntouched() {
        TsonValue value = new TsonTreeReader().read("{ person: { name: \"Ada\"  tags: [ a b c ] } }");

        assertEquals("Ada", value.at("/person/name").asString().orElseThrow());
    }
}
