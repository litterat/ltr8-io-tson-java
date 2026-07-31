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

    private static TsonReadContext contextOver(TsonEvent... events) {
        return TsonReadContext.collecting(new ListEventSource(List.of(events)));
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
    void withSchemaPositionReplacesOnlySchemaPosition() {
        TsonReadContext ctx = contextOver(token("42", new Position(1, 1, 0)));
        ctx.peek();
        TsonReadContext descended = ctx.field("x");

        SourcePosition schemaPosition = new Position(9, 2, 99);
        TsonReadContext restamped = descended.withSchemaPosition(Optional.of(schemaPosition));

        assertEquals(Optional.of(schemaPosition), restamped.schemaPosition());
        assertEquals(descended.position(), restamped.position());
        assertEquals(descended.path(), restamped.path());
    }

    @Test
    void throwingContextFailFastIsTrueAndThrowsImmediatelyOnReport() {
        TsonReadContext ctx = TsonReadContext.throwing(new ListEventSource(List.of()));

        assertTrue(ctx.failFast());
        TsonReadException thrown = assertThrows(TsonReadException.class,
                () -> ctx.report(Diagnostic.Code.TYPE_MISMATCH, "boom", "a thing", "another thing"));

        assertEquals("boom", thrown.getMessage());
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, thrown.diagnostic().code());
        assertEquals("a thing", thrown.diagnostic().expected());
        assertEquals("another thing", thrown.diagnostic().actual());
    }

    @Test
    void collectingContextFailFastIsFalseAndAccumulatesWithoutThrowing() {
        TsonReadContext ctx = TsonReadContext.collecting(new ListEventSource(List.of()));

        assertFalse(ctx.failFast());
        ctx.report(Diagnostic.Code.FIELD_REQUIRED, "first problem", "x", "y");
        ctx.field("nested").report(Diagnostic.Code.TYPE_MISMATCH, "second problem", "a", "b");

        assertEquals(2, ctx.diagnostics().size());
        assertEquals("first problem", ctx.diagnostics().get(0).message());
        assertEquals("", ctx.diagnostics().get(0).path());
        assertEquals("second problem", ctx.diagnostics().get(1).message());
        assertEquals("/nested", ctx.diagnostics().get(1).path());
    }

    @Test
    void reportedDiagnosticCarriesTheCurrentPathPositionAndSchemaPosition() {
        Position dataPosition = new Position(4, 2, 30);
        SourcePosition schemaPosition = new Position(10, 1, 100);
        TsonReadContext ctx = contextOver(token("42", dataPosition));

        TsonReadContext scoped = ctx.withSchemaPosition(Optional.of(schemaPosition)).field("value");
        scoped.peek();
        scoped.report(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, "out of range", "0..100", "200");

        Diagnostic diagnostic = ctx.diagnostics().get(0);
        assertEquals("/value", diagnostic.path());
        assertEquals(Optional.of(dataPosition), diagnostic.dataPosition());
        assertEquals(Optional.of(schemaPosition), diagnostic.schemaPosition());
    }
}
