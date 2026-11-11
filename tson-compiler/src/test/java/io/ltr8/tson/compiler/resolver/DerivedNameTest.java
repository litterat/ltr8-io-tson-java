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
class DerivedNameTest {

    private static TypeRef type(String name) {
        return TypeRef.of(name);
    }

    @Test
    void anApplicationIsRenderedByItsHeadAndArguments() {
        assertEquals("A3:box(rn4:text())", DerivedName.canonicalApplication("box", List.of(type("text"))));
        assertEquals(DerivedName.canonicalApplication("pair", List.of(type("uuid"), type("text"))),
                DerivedName.canonicalApplication("pair", List.of(type("uuid"), type("text"))));
    }

    /** A nested argument is its parsed form, so no spelling of names can stand for another arrangement of them. */
    @Test
    void theRenderingIsInjective() {
        assertNotEquals(DerivedName.canonicalApplication("pair", List.of(type("a"), type("b"))),
                DerivedName.canonicalApplication("pair", List.of(TypeRef.of("a", List.of(type("b"))))));
        assertNotEquals(DerivedName.canonicalApplication("box", List.of(type("a(b)"))),
                DerivedName.canonicalApplication("box", List.of(TypeRef.of("a", List.of(type("b"))))));
    }
}
