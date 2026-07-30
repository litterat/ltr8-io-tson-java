package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.schema.meta.SourcePosition;
import org.junit.jupiter.api.Test;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TsonReadContextTest {

    private static DataValue tokenValue(String text) {
        return new DataValue(List.of(), Optional.empty(), new TokenValue(text, TokenForm.UNQUOTED));
    }

    @Test
    void pathAccumulatesAcrossFieldAndIndexDescent() {
        TsonReadContext ctx = TsonReadContext.collecting(Map.of());

        TsonReadContext descended = ctx.field("orders", tokenValue("x")).index(3, tokenValue("y")).field("total", tokenValue("z"));

        assertEquals("/orders/3/total", descended.path());
    }

    @Test
    void pathEscapesTildeAndSlashPerRfc6901() {
        TsonReadContext ctx = TsonReadContext.collecting(Map.of());

        TsonReadContext descended = ctx.field("a/b~c", null);

        assertEquals("/a~1b~0c", descended.path());
    }

    @Test
    void positionResolvesFromTheSuppliedDataPositionsMap() {
        DataValue value = tokenValue("42");
        Position position = new Position(3, 5, 12);
        Map<CoreValue, Position> positions = new IdentityHashMap<>();
        positions.put(value.coreValue(), position);
        TsonReadContext ctx = TsonReadContext.collecting(positions);

        TsonReadContext at = ctx.at(value);

        assertEquals(Optional.of(position), at.position());
    }

    @Test
    void positionIsAbsentForAValueNotInTheSuppliedMap() {
        TsonReadContext ctx = TsonReadContext.collecting(Map.of());

        TsonReadContext at = ctx.at(tokenValue("unrecorded"));

        assertTrue(at.position().isEmpty());
    }

    @Test
    void fieldKeepsTheParentsPositionWhenTheChildValueIsMissing() {
        DataValue value = tokenValue("42");
        Position position = new Position(6, 3, 42);
        Map<CoreValue, Position> positions = new IdentityHashMap<>();
        positions.put(value.coreValue(), position);
        TsonReadContext ctx = TsonReadContext.collecting(positions).at(value);

        TsonReadContext missingField = ctx.field("value", null);

        assertEquals(Optional.of(position), missingField.position());
        assertEquals("/value", missingField.path());
    }

    @Test
    void indexAlsoKeepsTheParentsPositionWhenTheElementIsMissing() {
        DataValue value = tokenValue("42");
        Position position = new Position(1, 1, 0);
        Map<CoreValue, Position> positions = new IdentityHashMap<>();
        positions.put(value.coreValue(), position);
        TsonReadContext ctx = TsonReadContext.collecting(positions).at(value);

        TsonReadContext missingElement = ctx.index(0, null);

        assertEquals(Optional.of(position), missingElement.position());
        assertEquals("/0", missingElement.path());
    }

    @Test
    void withSchemaPositionReplacesOnlySchemaPosition() {
        DataValue value = tokenValue("42");
        Position dataPosition = new Position(1, 1, 0);
        Map<CoreValue, Position> positions = new IdentityHashMap<>();
        positions.put(value.coreValue(), dataPosition);
        TsonReadContext ctx = TsonReadContext.collecting(positions).at(value).field("x", value);

        SourcePosition schemaPosition = new Position(9, 2, 99);
        TsonReadContext restamped = ctx.withSchemaPosition(Optional.of(schemaPosition));

        assertEquals(Optional.of(schemaPosition), restamped.schemaPosition());
        assertEquals(ctx.position(), restamped.position());
        assertEquals(ctx.path(), restamped.path());
    }

    @Test
    void throwingContextFailFastIsTrueAndThrowsImmediatelyOnReport() {
        TsonReadContext ctx = TsonReadContext.throwing();

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
        TsonReadContext ctx = TsonReadContext.collecting(Map.of());

        assertFalse(ctx.failFast());
        ctx.report(Diagnostic.Code.FIELD_REQUIRED, "first problem", "x", "y");
        ctx.field("nested", null).report(Diagnostic.Code.TYPE_MISMATCH, "second problem", "a", "b");

        assertEquals(2, ctx.diagnostics().size());
        assertEquals("first problem", ctx.diagnostics().get(0).message());
        assertEquals("", ctx.diagnostics().get(0).path());
        assertEquals("second problem", ctx.diagnostics().get(1).message());
        assertEquals("/nested", ctx.diagnostics().get(1).path());
    }

    @Test
    void reportedDiagnosticCarriesTheCurrentPathPositionAndSchemaPosition() {
        DataValue value = tokenValue("42");
        Position dataPosition = new Position(4, 2, 30);
        Map<CoreValue, Position> positions = new IdentityHashMap<>();
        positions.put(value.coreValue(), dataPosition);
        SourcePosition schemaPosition = new Position(10, 1, 100);

        TsonReadContext ctx = TsonReadContext.collecting(positions).at(value)
                .withSchemaPosition(Optional.of(schemaPosition))
                .field("value", value);
        ctx.report(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, "out of range", "0..100", "200");

        Diagnostic diagnostic = ctx.diagnostics().get(0);
        assertEquals("/value", diagnostic.path());
        assertEquals(Optional.of(dataPosition), diagnostic.dataPosition());
        assertEquals(Optional.of(schemaPosition), diagnostic.schemaPosition());
    }
}
