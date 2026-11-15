package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.AnnotatedMap;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The Java model's kinds mirror the kernel's composition (Part 2 §4.1): {@code atom},
 * {@code product} and {@code sum} compose with {@code type}, and {@code alias} and
 * {@code namespace} with {@code top} directly -- a {@link Namespace} being the value of a namespace type.
 */
class EntryKindHierarchyTest {

    /** The three kinds, and an application, which stands for a type of no kind until its open record is applied. */
    @Test
    void theThreeKindsOfDataValuesAndAnApplicationAreTypes() {
        assertEquals(Set.of(Atom.class, Product.class, Sum.class, io.ltr8.tson.schema.meta.ApplyBody.class),
                Set.of(Type.class.getPermittedSubclasses()));
    }

    @Test
    void whatComposesWithTopDirectlyIsNoType() {
        assertEquals(Set.of(Type.class, Alias.class, ValueEntry.class, IncompleteEntry.class),
                Set.of(Top.class.getPermittedSubclasses()));
        assertFalse(Type.class.isAssignableFrom(Alias.class));
        assertFalse(Type.class.isAssignableFrom(ValueEntry.class));
        assertFalse(Type.class.isAssignableFrom(IncompleteEntry.class));
    }

    @Test
    void anIncompleteEntryTakesTheIncompleteKindAndNamesItsConstructor() {
        IncompleteEntry nonempty = new IncompleteEntry("meta.array", java.util.List.of("element_type"), "written", Map.of());
        assertEquals(EntryKind.INCOMPLETE, EntryKind.of(nonempty, name -> true));
        assertEquals("meta.array", io.ltr8.tson.schema.TsonSchema.constructorOf(nonempty));
    }

    @Test
    void anIncompleteEntryWithNothingAbsentIsABrokenInvariant() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> new IncompleteEntry("meta.array", java.util.List.of(), "written", Map.of()));
    }

    @Test
    void aValueEntryTakesTheValueKind() {
        ValueEntry orders = new ValueEntry("api", new Namespace<>("api", AnnotatedMap.of(Map.of())));
        assertEquals(EntryKind.VALUE, EntryKind.of(orders, name -> true));
    }

    @Test
    void aNamespaceTypeIsAProductLikeAMapType() {
        NamespaceBody api = NamespaceBody.of(TypeRef.of("meta.identifier"), TypeRef.of("method"), false);
        assertEquals(EntryKind.PRODUCT, EntryKind.of(api, name -> true));
        assertInstanceOf(Product.class, api);
    }
}
