package io.ltr8.tson.schema.meta;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@link QualifiedName}'s structure: segments joined by {@code .}, none empty, written back as they were read. */
class QualifiedNameTest {

    @Test
    void aDottedTokenSplitsIntoItsSegments() {
        QualifiedName name = QualifiedName.split("orders.methods.place_order").orElseThrow();
        assertEquals(List.of("orders", "methods", "place_order"), name.segments());
        assertEquals("orders", name.head());
        assertEquals("orders.methods.place_order", name.toString());
    }

    @Test
    void oneSegmentIsAName() {
        assertEquals(QualifiedName.of("place_order_request"), QualifiedName.split("place_order_request").orElseThrow());
    }

    @Test
    void anEmptySegmentIsNoName() {
        assertEquals(Optional.empty(), QualifiedName.split("a..b"));
        assertEquals(Optional.empty(), QualifiedName.split(".a"));
        assertEquals(Optional.empty(), QualifiedName.split("a."));
        assertEquals(Optional.empty(), QualifiedName.split(""));
    }

    @Test
    void aSegmentHoldsNoSeparator() {
        assertThrows(IllegalArgumentException.class, () -> QualifiedName.of("a.b"));
        assertThrows(IllegalArgumentException.class, () -> QualifiedName.of());
    }

    /** {@link Data.Named}'s slot is a path into the body, and a name fills a slot of it, never the body itself. */
    @Test
    void aNamedValueHasASlot() {
        Data.Named named = Data.Named.at(QualifiedName.of("x"), "methods", "m", "request");
        assertEquals(List.of("methods", "m", "request"), named.slot());
        assertThrows(IllegalArgumentException.class, () -> Data.Named.at(QualifiedName.of("x")));
    }
}
