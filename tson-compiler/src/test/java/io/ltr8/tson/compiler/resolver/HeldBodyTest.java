package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.ast.schema.ReferenceTypeDef;
import io.ltr8.tson.compiler.ast.schema.SimpleRef;
import io.ltr8.tson.schema.meta.OpenBody;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The two properties the held-body carrier is chosen for. Neither is visible from its declaration: one is a
 * module-boundary arrangement and the other is a decision about {@link TypeDefinition}'s equality that goes
 * the opposite way from the component beside it.
 */
class HeldBodyTest {

    private static TypeDefinition openEntry(String heldTarget) {
        return new TypeDefinition(Optional.empty(), TypeKind.REFERENCE, List.of("T"), false, List.of(),
                List.of(), Optional.empty(),
                new HeldBody(new ReferenceTypeDef(List.of("T"), new SimpleRef(heldTarget))));
    }

    /**
     * The point of declaring {@link OpenBody} in {@code schema.meta} at all: {@code tson-compiler}'s own AST
     * can stand as a resolved body without {@code tson-schema} naming a {@code tson-compiler} type, so an
     * open entry needs no second body component and {@code TypeDefinition.body} stays REQUIRED.
     */
    @Test
    void aHeldDeclarationIsABodyTheValueModelCanCarry() {
        Top body = openEntry("text").body();

        assertInstanceOf(OpenBody.class, body);
        assertInstanceOf(HeldBody.class, body);
    }

    /**
     * Held bodies are content, so they participate in equality -- unlike {@code position}, which is excluded
     * so that two parses of one declaration compare equal. Two templates with different bodies are different
     * templates, and nothing else on the entry distinguishes them.
     */
    @Test
    void twoTemplatesDifferingOnlyInTheirHeldBodyAreNotEqual() {
        assertEquals(openEntry("text"), openEntry("text"));
        assertNotEquals(openEntry("text"), openEntry("int32"));
    }

    @Test
    void thereIsAlwaysADeclarationToHold() {
        assertThrows(IllegalArgumentException.class, () -> new HeldBody(null));
    }
}
