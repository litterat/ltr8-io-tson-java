package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.schema.meta.ApplyBody;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * [TSON-SCHEMA] §8.2's canonical application: two applications are one exactly when their heads and arguments are
 * equal, compared as structure.
 */
class CanonicalApplicationTest {

    private static TypeRef type(String name) {
        return TypeRef.of(name);
    }

    @Test
    void anApplicationIsItsHeadAndArguments() {
        assertEquals(ApplyBody.of("box", "text"), CanonicalApplication.of("box", List.of(type("text"))));
        assertEquals(CanonicalApplication.of("pair", List.of(type("uuid"), type("text"))),
                CanonicalApplication.of("pair", List.of(type("uuid"), type("text"))));
    }

    /** Structure, never spelling: a name holding delimiters is one name, apart from any arrangement of others. */
    @Test
    void twoArrangementsOfNamesAreTwoApplications() {
        assertNotEquals(CanonicalApplication.of("pair", List.of(type("a"), type("b"))),
                CanonicalApplication.of("pair", List.of(type("b"), type("a"))));
        assertNotEquals(CanonicalApplication.of("box", List.of(type("a(b)"))),
                CanonicalApplication.of("box", List.of(type("a"))));
    }
}
