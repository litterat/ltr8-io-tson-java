package io.ltr8.tson.schema.meta;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The Java model's kinds mirror the kernel's composition (Part 2 §4.1, SPEC-FEEDBACK.md #9): {@code atom},
 * {@code product} and {@code sum} compose with {@code type}, and {@code data}, {@code reference} and
 * {@code template} with {@code top} directly.
 */
class TypeKindHierarchyTest {

    @Test
    void theThreeKindsOfDataValuesAreTypes() {
        assertEquals(Set.of(Atom.class, Product.class, Sum.class), Set.of(Type.class.getPermittedSubclasses()));
    }

    @Test
    void whatComposesWithTopDirectlyIsNoType() {
        assertEquals(Set.of(Type.class, Reference.class, Data.class, TemplateBody.class),
                Set.of(Top.class.getPermittedSubclasses()));
        assertFalse(Type.class.isAssignableFrom(Data.class));
        assertFalse(Type.class.isAssignableFrom(Reference.class));
        assertFalse(Type.class.isAssignableFrom(TemplateBody.class));
    }
}
