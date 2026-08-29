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

    private static final String SCHEMA_ID = "example.test/s.tn";

    private static TokenEvent token(String text, Position position) {
        return new TokenEvent(text, TokenForm.UNQUOTED, position);
    }

    /** A context over {@code events} whose diagnostics are collected but not inspected -- for the cursor/path tests. */
    private static TsonReadContext contextOver(TsonEvent... events) {
        return TsonReadContext.of(new ListEventSource(List.of(events)), new TsonDiagnosticsCollector(),
                TsonUnicodePolicy.unrestricted());
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
    void inRecordReplacesOnlyTheSchemaLocation() {
        TsonReadContext ctx = contextOver(token("42", new Position(1, 1, 0)));
        ctx.peek();
        TsonReadContext descended = ctx.field("x");

        SchemaLocation location = SchemaLocation.of(SCHEMA_ID, "my_type", Optional.of(new Position(9, 2, 99)));
        TsonReadContext restamped = descended.inRecord(location);

        assertEquals(Optional.of(location), restamped.schemaLocation());
        assertEquals(descended.position(), restamped.position());
        assertEquals(descended.path(), restamped.path());
    }

    /**
     * The outermost record roots the pointer; an inner one extends nothing but takes over the anchor, because
     * it is what declares the field the pointer now ends with.
     */
    @Test
    void anInnerRecordReanchorsThePointerItDoesNotRestartIt() {
        TsonReadContext ctx = contextOver(token("42", new Position(1, 1, 0)));
        SchemaLocation outer = SchemaLocation.of("a.test/s.tn", "person", Optional.of(new Position(2, 1, 10)));
        SchemaLocation inner = SchemaLocation.of("a.test/s.tn", "address", Optional.of(new Position(6, 1, 40)));

        SchemaLocation reached = ctx.inRecord(outer).schemaField("home", Optional.empty())
                .inRecord(inner).schemaField("city", Optional.empty())
                .schemaLocation().orElseThrow();

        assertEquals("/person/home/city", reached.pointer());
        assertEquals(inner.position(), reached.position(), "the record that declares 'city'");
    }

    /**
     * A declaration offered by a non-record reader is a seed, not a claim: it locates a value read at the root
     * of a document, and is ignored the moment anything encloses it.
     */
    @Test
    void underDeclarationSeedsOnlyWhenNothingIsAlreadyAnchored() {
        TsonReadContext ctx = contextOver(token("42", new Position(1, 1, 0)));
        SchemaLocation atom = SchemaLocation.of("tson.io/core.tn", "int32", Optional.of(new Position(110, 3, 4858)));
        SchemaLocation record = SchemaLocation.of("a.test/s.tn", "point", Optional.of(new Position(4, 1, 20)));

        assertEquals(Optional.of(atom), ctx.underDeclaration(atom).schemaLocation());

        SchemaLocation enclosed = ctx.inRecord(record).schemaField("y", Optional.empty()).underDeclaration(atom)
                .schemaLocation().orElseThrow();
        assertEquals("/point/y", enclosed.pointer());
        assertEquals("a.test/s.tn", enclosed.schemaId());
    }

    /** A map key or array index is a data step alone -- the schema says one thing about every entry. */
    @Test
    void fieldAndIndexStepTheDataPathWithoutSteppingTheSchemaPointer() {
        TsonReadContext ctx = contextOver(token("42", new Position(1, 1, 0)));
        SchemaLocation record = SchemaLocation.of("a.test/s.tn", "person", Optional.of(new Position(2, 1, 10)));

        TsonReadContext scoped = ctx.inRecord(record).schemaField("tags", Optional.empty()).field("some-key").index(3);

        assertEquals("/tags/some-key/3", scoped.path());
        assertEquals("/person/tags", scoped.schemaLocation().orElseThrow().pointer());
    }

    @Test
    void theThrowingReceiverThrowsImmediatelyOnReport() {
        TsonReadContext ctx = TsonReadContext.throwing(new ListEventSource(List.of()), TsonUnicodePolicy.unrestricted());

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
        TsonReadContext ctx = TsonReadContext.of(new ListEventSource(List.of()), problems, TsonUnicodePolicy.unrestricted());

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
        TsonReadContext ctx = TsonReadContext.of(new ListEventSource(List.of()), diagnostic -> { },
                TsonUnicodePolicy.unrestricted());

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
                new ListEventSource(List.of(token("42", dataPosition))), problems, TsonUnicodePolicy.unrestricted());

        TsonReadContext scoped =
                ctx.inRecord(SchemaLocation.of(SCHEMA_ID, "my_type", Optional.of(schemaPosition))).field("value");
        scoped.peek();
        scoped.report(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, "out of range", "0..100", "200");

        Diagnostic diagnostic = problems.diagnostics().get(0);
        assertEquals(Optional.of("/value"), diagnostic.path());
        assertEquals(Optional.of(dataPosition), diagnostic.dataPosition());
        assertEquals(SCHEMA_ID, diagnostic.schemaId());
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
                new ListEventSource(List.of(token("42", new Position(1, 1, 0)))), problems,
                TsonUnicodePolicy.unrestricted());
        ctx.peek();

        ctx.report(Diagnostic.Code.TYPE_MISMATCH, "wrong shape", "a record", "a token");

        Diagnostic diagnostic = problems.diagnostics().get(0);
        assertEquals("", diagnostic.schemaId());
        assertEquals(Optional.empty(), diagnostic.schemaPointer());
        assertEquals(Optional.empty(), diagnostic.schemaPosition());
    }
}
