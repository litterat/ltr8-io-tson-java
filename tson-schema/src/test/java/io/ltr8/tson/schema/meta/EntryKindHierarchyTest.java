package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.AnnotatedMap;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The Java model's kinds mirror the kernel's composition (Part 2 §4.1, SPEC-FEEDBACK.md #9): {@code atom},
 * {@code product} and {@code sum} compose with {@code type}, and {@code reference}, {@code template} and
 * {@code namespace} with {@code top} directly -- a {@link Namespace} being the value of a namespace type.
 */
class EntryKindHierarchyTest {

    @Test
    void theThreeKindsOfDataValuesAreTypes() {
        assertEquals(Set.of(Atom.class, Product.class, Sum.class), Set.of(Type.class.getPermittedSubclasses()));
    }

    @Test
    void whatComposesWithTopDirectlyIsNoType() {
        assertEquals(Set.of(Type.class, Reference.class, TemplateBody.class, Namespace.class),
                Set.of(Top.class.getPermittedSubclasses()));
        assertFalse(Type.class.isAssignableFrom(Reference.class));
        assertFalse(Type.class.isAssignableFrom(TemplateBody.class));
        assertFalse(Type.class.isAssignableFrom(Namespace.class));
    }

    @Test
    void aNamespaceValueTakesTheNamespaceKind() {
        assertEquals(EntryKind.NAMESPACE, EntryKind.of(new Namespace<>("api", AnnotatedMap.of(Map.of())), name -> true));
    }
}
