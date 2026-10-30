package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.AnnotatedMap;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The Java model's kinds mirror the kernel's composition (Part 2 §4.1): {@code atom},
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
        assertEquals(Set.of(Type.class, Reference.class, TemplateBody.class, ValueEntry.class),
                Set.of(Top.class.getPermittedSubclasses()));
        assertFalse(Type.class.isAssignableFrom(Reference.class));
        assertFalse(Type.class.isAssignableFrom(TemplateBody.class));
        assertFalse(Type.class.isAssignableFrom(ValueEntry.class));
    }

    @Test
    void aValueEntryTakesTheValueKind() {
        ValueEntry orders = new ValueEntry("api", new Namespace<>("api", AnnotatedMap.of(Map.of())));
        assertEquals(EntryKind.VALUE, EntryKind.of(orders, name -> true));
    }

    @Test
    void aNamespaceTypeIsAProductLikeAMapType() {
        NamespaceBody api = new NamespaceBody(Optional.of(TypeRef.of("identifier")), TypeRef.of("method"), false);
        assertEquals(EntryKind.PRODUCT, EntryKind.of(api, name -> true));
        assertInstanceOf(Product.class, api);
    }
}
