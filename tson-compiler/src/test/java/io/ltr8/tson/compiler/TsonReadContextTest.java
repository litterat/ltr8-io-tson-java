package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.stream.ListEventSource;
import io.ltr8.tson.compiler.stream.TokenEvent;
import io.ltr8.tson.compiler.stream.TsonEvent;
import io.ltr8.tson.schema.meta.SourcePosition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TsonReadContextTest {

    private static TokenEvent token(String text, Position position) {
        return new TokenEvent(text, TokenForm.UNQUOTED, position);
    }

    /** A context over {@code events} whose diagnostics are collected but not inspected -- for the cursor/path tests. */
    private static TsonReadContext contextOver(TsonEvent... events) {
        return TsonReadContext.of(new ListEventSource(List.of(events)), new TsonDiagnosticsCollector());
    }

    @Test
    void pathAccumulatesAcrossFieldAndIndexDescent() {
        TsonReadContext ctx = contextOver(token("x", new Position(1, 1, 0)));

        TsonReadContext descended = ctx.field("orders").index(3).field("total");

        assertEquals("/orders/3/total", descended.path());
    }

    @Test
    void pathEscapesTildeAndSlashPerRfc6901() {
        TsonReadContext ctx = contextOver(token("x", new Position(1, 1, 0)));

        TsonReadContext descended = ctx.field("a/b~c");

        assertEquals("/a~1b~0c", descended.path());
    }

    @Test
    void positionIsEmptyUntilSomethingIsActuallyPeekedOrConsumed() {
        TsonReadContext ctx = contextOver(token("42", new Position(3, 5, 12)));

        assertTrue(ctx.position().isEmpty());
    }

    @Test
    void positionReflectsTheMostRecentlyPeekedOrConsumedEvent() {
        Position position = new Position(3, 5, 12);
        TsonReadContext ctx = contextOver(token("42", position));

        ctx.peek();

        assertEquals(Optional.of(position), ctx.position());
    }

    /** There is only ever one real cursor per read -- every scoped copy shares it, so pulling an event through any one copy is visible to all of them. */
    @Test
    void positionIsSharedAcrossEveryScopedCopyOfTheSameRead() {
        Position position = new Position(6, 3, 42);
        TsonReadContext ctx = contextOver(token("42", position));
        TsonReadContext descended = ctx.field("value");

        descended.peek();

        assertEquals(Optional.of(position), ctx.position());
        assertEquals(Optional.of(position), descended.position());
    }

    @Test
    void withPositionOverridesOnlyThePinnedCopysOwnPosition() {
        Position live = new Position(1, 1, 0);
        Position pinned = new Position(9, 9, 90);
        TsonReadContext ctx = contextOver(token("42", live));
        ctx.peek();

        TsonReadContext anchored = ctx.withPosition(Optional.of(pinned));

        assertEquals(Optional.of(pinned), anchored.position());
        assertEquals(Optional.of(live), ctx.position());
    }

    @Test
    void withSchemaLocationReplacesOnlyTheSchemaLocation() {
        TsonReadContext ctx = contextOver(token("42", new Position(1, 1, 0)));
        ctx.peek();
        TsonReadContext descended = ctx.field("x");

        SchemaLocation location = new SchemaLocation("my_type", Optional.of(new Position(9, 2, 99)));
        TsonReadContext restamped = descended.withSchemaLocation(location);

        assertEquals(Optional.of(location), restamped.schemaLocation());
        assertEquals(descended.position(), restamped.position());
        assertEquals(descended.path(), restamped.path());
    }

    @Test
    void theThrowingReceiverThrowsImmediatelyOnReport() {
        TsonReadContext ctx = TsonReadContext.throwing(new ListEventSource(List.of()));

        TsonReadException thrown = assertThrows(TsonReadException.class,
                () -> ctx.report(Diagnostic.Code.TYPE_MISMATCH, "boom", "a thing", "another thing"));

        assertEquals("boom", thrown.getMessage());
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, thrown.diagnostic().code());
        assertEquals("a thing", thrown.diagnostic().expected());
        assertEquals("another thing", thrown.diagnostic().actual());
    }

    @Test
    void aCollectingReceiverAccumulatesWithoutThrowing() {
        TsonDiagnosticsCollector problems = new TsonDiagnosticsCollector();
        TsonReadContext ctx = TsonReadContext.of(new ListEventSource(List.of()), problems);

        assertTrue(problems.isEmpty());
        ctx.report(Diagnostic.Code.FIELD_REQUIRED, "first problem", "x", "y");
        ctx.field("nested").report(Diagnostic.Code.TYPE_MISMATCH, "second problem", "a", "b");

        assertEquals(2, problems.diagnostics().size());
        assertEquals("first problem", problems.diagnostics().get(0).message());
        assertEquals(Optional.of(""), problems.diagnostics().get(0).path());
        assertEquals("second problem", problems.diagnostics().get(1).message());
        assertEquals(Optional.of("/nested"), problems.diagnostics().get(1).path());
    }

    @Test
    void reportedCountsEveryProblemAcrossScopedCopiesWhateverTheReceiverDoesWithThem() {
        // A receiver that keeps nothing at all -- reported() still has to answer, since that is what the
        // readers' own "did my children complain?" checkpoints are built on.
        TsonReadContext ctx = TsonReadContext.of(new ListEventSource(List.of()), diagnostic -> { });

        assertEquals(0, ctx.reported());
        ctx.report(Diagnostic.Code.FIELD_REQUIRED, "first", "x", "y");
        assertEquals(1, ctx.reported());

        TsonReadContext scoped = ctx.field("nested");
        scoped.report(Diagnostic.Code.TYPE_MISMATCH, "second", "a", "b");

        // One cursor per read, so the count is shared by every copy, in both directions.
        assertEquals(2, scoped.reported());
        assertEquals(2, ctx.reported());
    }

    @Test
    void reportedDiagnosticCarriesTheCurrentPathPositionAndSchemaLocation() {
        Position dataPosition = new Position(4, 2, 30);
        SourcePosition schemaPosition = new Position(10, 1, 100);
        TsonDiagnosticsCollector problems = new TsonDiagnosticsCollector();
        TsonReadContext ctx = TsonReadContext.of(
                new ListEventSource(List.of(token("42", dataPosition))), problems);

        TsonReadContext scoped =
                ctx.withSchemaLocation(new SchemaLocation("my_type", Optional.of(schemaPosition))).field("value");
        scoped.peek();
        scoped.report(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, "out of range", "0..100", "200");

        Diagnostic diagnostic = problems.diagnostics().get(0);
        assertEquals(Optional.of("/value"), diagnostic.path());
        assertEquals(Optional.of(dataPosition), diagnostic.dataPosition());
        assertEquals(Optional.of("/my_type"), diagnostic.schemaPointer());
        assertEquals(Optional.of(schemaPosition), diagnostic.schemaPosition());
    }

    /**
     * A read with no schema behind it -- {@code TsonReadContext.of} starts with no location, and nothing
     * stamps one, so both schema-end components stay absent rather than becoming a present root pointer.
     */
    @Test
    void aReportWithNoSchemaLocationStampedCarriesNeitherPointerNorPosition() {
        TsonDiagnosticsCollector problems = new TsonDiagnosticsCollector();
        TsonReadContext ctx = TsonReadContext.of(
                new ListEventSource(List.of(token("42", new Position(1, 1, 0)))), problems);
        ctx.peek();

        ctx.report(Diagnostic.Code.TYPE_MISMATCH, "wrong shape", "a record", "a token");

        Diagnostic diagnostic = problems.diagnostics().get(0);
        assertEquals(Optional.empty(), diagnostic.schemaPointer());
        assertEquals(Optional.empty(), diagnostic.schemaPosition());
    }
}
