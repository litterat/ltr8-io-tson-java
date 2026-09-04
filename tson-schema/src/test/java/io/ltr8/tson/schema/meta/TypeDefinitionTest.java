package io.ltr8.tson.schema.meta;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * {@link TypeDefinition#equals}/{@link TypeDefinition#hashCode} deliberately exclude {@code
 * position} -- see that class's own Javadoc for why (heavy structural comparison throughout {@code
 * DefinitionResolverTest}, hand-built expected value vs. a real resolved one, which would break the
 * moment two logically-identical {@code TypeDefinition}s came from different parses).
 */
class TypeDefinitionTest {

    private record TestPosition(int line, int column, int byteOffset) implements SourcePosition {
    }

    private static TypeDefinition unit(Optional<SourcePosition> position) {
        return new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), 
                List.of(), List.of(), Optional.empty(), new Unit(), position);
    }

    @Test
    void instancesDifferingOnlyInPositionCompareEqual() {
        TypeDefinition withPosition = unit(Optional.of(new TestPosition(4, 7, 42)));
        TypeDefinition withoutPosition = unit(Optional.empty());
        TypeDefinition withDifferentPosition = unit(Optional.of(new TestPosition(99, 1, 0)));

        assertEquals(withPosition, withoutPosition);
        assertEquals(withPosition, withDifferentPosition);
        assertEquals(withPosition.hashCode(), withoutPosition.hashCode());
        assertEquals(withPosition.hashCode(), withDifferentPosition.hashCode());
    }

    @Test
    void withPositionReturnsAnOtherwiseEqualCopy() {
        TypeDefinition original = unit(Optional.empty());
        TypeDefinition repositioned = original.withPosition(Optional.of(new TestPosition(1, 1, 0)));

        assertEquals(original, repositioned);
        assertEquals(Optional.empty(), original.position());
        assertEquals(1, repositioned.position().orElseThrow().line());
    }

    @Test
    void aGenuineStructuralDifferenceStillCompareUnequal() {
        TypeDefinition unitEntry = unit(Optional.empty());
        TypeDefinition otherKind = new TypeDefinition(Optional.empty(), TypeKind.SUM, List.of(), 
                List.of(), List.of(), Optional.empty(), new Scoped(List.of(ScopeKind.LOCAL), Optional.empty()), Optional.empty());

        assertNotEquals(unitEntry, otherKind);
    }
}
