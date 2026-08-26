package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.schema.meta.TemplateBody;
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

    /** {@code box => <T> !<head> {}} -- the smallest held application that differs by its constructor. */
    private static TypeDefinition template(String head) {
        return new TypeDefinition(Optional.empty(), TypeKind.PRODUCT, List.of("T"), false, List.of(),
                List.of(), Optional.empty(),
                new HeldBody(new DataValue(List.of(), Optional.of(head), new RecordValue(List.of()))));
    }

    /**
     * The point of declaring {@link TemplateBody} in {@code schema.meta} at all: {@code tson-compiler}'s own
     * AST can stand as a resolved body without {@code tson-schema} naming a {@code tson-compiler} type, so a
     * template needs no second body component and {@code TypeDefinition.body} stays REQUIRED.
     */
    @Test
    void aHeldApplicationIsABodyTheValueModelCanCarry() {
        Top body = template("record").body();

        assertInstanceOf(TemplateBody.class, body);
        assertInstanceOf(HeldBody.class, body);
    }

    /**
     * Held bodies are content, so they participate in equality -- unlike {@code position}, which is excluded
     * so that two parses of one declaration compare equal. Two templates with different bodies are different
     * templates, and nothing else on the entry distinguishes them.
     */
    @Test
    void twoTemplatesDifferingOnlyInTheirHeldBodyAreNotEqual() {
        assertEquals(template("record"), template("record"));
        assertNotEquals(template("record"), template("array"));
    }

    @Test
    void thereIsAlwaysAnApplicationToHold() {
        assertThrows(IllegalArgumentException.class, () -> new HeldBody(null));
    }
}
