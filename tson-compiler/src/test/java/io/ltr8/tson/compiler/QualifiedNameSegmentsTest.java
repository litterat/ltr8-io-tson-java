package io.ltr8.tson.compiler;

import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.schema.meta.TypeRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The linker's parse of a reference written by name into its segments ({@link ValuesByName#segments}): the name split
 * at each {@code .} before any family is consulted, none empty, the first a [TSON-DATA] §7.7 identifier
 * ([TSON-SCHEMA] §5.12).
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
            assertTrue(refused.getMessage().contains("has an empty segment"), refused.getMessage());
        }
    }

    @Test
    void aFirstSegmentThatIsNoIdentifierIsNoName() {
        SchemaValidationException refused = assertThrows(SchemaValidationException.class,
                () -> segments("1x.orders"));
        assertTrue(refused.getMessage().contains("'1x' is no name"), refused.getMessage());
    }

    /** A further segment is the family's to judge where the walk enters it, so it splits whatever it holds. */
    @Test
    void aFurtherSegmentSplitsWhateverItHolds() {
        assertEquals(List.of("main", "public api", "ping"), segments("main.public api.ping"));
        assertEquals(List.of("orders", "1x"), segments("orders.1x"));
    }

}
