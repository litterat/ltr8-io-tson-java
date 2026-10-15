package io.ltr8.tson.compiler;

import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.schema.meta.TypeArgument;
import io.ltr8.tson.schema.meta.TypeRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The linker's parse of a reference written by name into its segments ({@link ValuesByName#segments}): the name split
 * at each {@code .}, each segment a [TSON-DATA] §7.7 identifier.
 */
class QualifiedNameSegmentsTest {

    private static List<String> segments(String name) {
        return ValuesByName.segments("'x' at /m", TypeRef.of(name));
    }

    @Test
    void aDottedNameSplitsIntoItsSegments() {
        assertEquals(List.of("orders", "methods", "place_order"), segments("orders.methods.place_order"));
        assertEquals(List.of("place_order_request"), segments("place_order_request"));
        assertEquals(List.of("kebab-name"), segments("kebab-name"));
    }

    @Test
    void anEmptySegmentIsNoName() {
        for (String name : List.of(".orders", "orders.", "a..b")) {
            SchemaValidationException refused = assertThrows(SchemaValidationException.class, () -> segments(name));
            assertTrue(refused.getMessage().contains("one is empty"), refused.getMessage());
        }
    }

    @Test
    void aSegmentThatIsNoIdentifierIsNoName() {
        SchemaValidationException refused = assertThrows(SchemaValidationException.class,
                () -> segments("orders.1x"));
        assertTrue(refused.getMessage().contains("'1x' is not"), refused.getMessage());
    }

    @Test
    void aReferenceWithArgumentsIsNoName() {
        TypeRef applied = new TypeRef("box", List.of(new TypeArgument.Ref(TypeRef.of("text"))));
        assertThrows(SchemaValidationException.class, () -> ValuesByName.segments("'x' at /m", applied));
    }
}
