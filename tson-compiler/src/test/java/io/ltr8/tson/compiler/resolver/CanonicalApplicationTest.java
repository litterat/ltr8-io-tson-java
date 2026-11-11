package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * [TSON-SCHEMA] §8.2's canonical application: two applications are one exactly when their renderings are equal,
 * an argument compared as its parsed form.
 */
class CanonicalApplicationTest {

    private static TypeRef type(String name) {
        return TypeRef.of(name);
    }

    @Test
    void anApplicationIsRenderedByItsHeadAndArguments() {
        assertEquals("A3:box(rn4:text())", CanonicalApplication.of("box", List.of(type("text"))));
        assertEquals(CanonicalApplication.of("pair", List.of(type("uuid"), type("text"))),
                CanonicalApplication.of("pair", List.of(type("uuid"), type("text"))));
    }

    /** A nested argument is its parsed form, so no spelling of names can stand for another arrangement of them. */
    @Test
    void theRenderingIsInjective() {
        assertNotEquals(CanonicalApplication.of("pair", List.of(type("a"), type("b"))),
                CanonicalApplication.of("pair", List.of(TypeRef.of("a", List.of(type("b"))))));
        assertNotEquals(CanonicalApplication.of("box", List.of(type("a(b)"))),
                CanonicalApplication.of("box", List.of(TypeRef.of("a", List.of(type("b"))))));
    }
}
