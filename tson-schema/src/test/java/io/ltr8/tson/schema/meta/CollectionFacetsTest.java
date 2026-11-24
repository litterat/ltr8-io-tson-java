package io.ltr8.tson.schema.meta;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollectionFacetsTest {

    private static final ArrayBody TAGS = new ArrayBody(TypeRef.of("text"), true, true, false, Optional.of(1L),
            Optional.of(9L));

    private static ArrayBody tags(boolean voidable, boolean ordered, boolean unique, Long min, Long max) {
        return new ArrayBody(TypeRef.of("text"), voidable, ordered, unique, Optional.ofNullable(min),
                Optional.ofNullable(max));
    }

    @Test
    void anArrayNarrowsByEachFacetsKind() {
        assertEquals(List.of(), CollectionFacets.narrowing(TAGS, tags(false, true, true, 2L, 8L)));
        assertEquals(List.of(), CollectionFacets.narrowing(TAGS, TAGS));
    }

    @Test
    void anArrayMovingAFacetOutwardIsReportedPerFacet() {
        List<String> violations = CollectionFacets.narrowing(tags(false, true, true, 2L, 8L),
                tags(true, false, false, 1L, 9L));
        assertEquals(5, violations.size(), violations.toString());
        assertTrue(violations.stream().anyMatch(each -> each.startsWith("ordered")), violations.toString());
        assertTrue(violations.stream().anyMatch(each -> each.startsWith("voidable")), violations.toString());
        assertTrue(violations.stream().anyMatch(each -> each.startsWith("duplicate items")), violations.toString());
    }

    @Test
    void aBoundTheSourceLeftOpenAdmitsAnyValue() {
        assertEquals(List.of(), CollectionFacets.narrowing(tags(false, true, false, null, null),
                tags(false, true, false, 0L, 3L)));
    }

    @Test
    void aTypeSlotIsRestatedOnly() {
        ArrayBody ids = new ArrayBody(TypeRef.of("uuid"), true, true, false, Optional.of(1L), Optional.of(9L));
        assertEquals(1, CollectionFacets.narrowing(TAGS, ids).size());
        MapBody index = MapBody.of(TypeRef.of("text"), TypeRef.of("int32"));
        assertEquals(List.of("key_type uuid replaces the source's own text, and a refinement only restates key_type"),
                CollectionFacets.narrowing(index, MapBody.of(TypeRef.of("uuid"), TypeRef.of("int32"))));
    }

    @Test
    void aNamespaceTypeNarrowsAsTheMapItsValuesAreReadBy() {
        NamespaceBody api = new NamespaceBody(TypeRef.of("meta.identifier"), TypeRef.of("method"), false,
                Optional.of(1L), Optional.of(5L));
        assertEquals(List.of(), CollectionFacets.narrowing(api, new NamespaceBody(TypeRef.of("meta.identifier"),
                TypeRef.of("method"), false, Optional.of(2L), Optional.of(3L))));
        List<String> violations = CollectionFacets.narrowing(api, new NamespaceBody(TypeRef.of("meta.type_name"),
                TypeRef.of("text"), true, Optional.of(0L), Optional.of(6L)));
        assertEquals(5, violations.size(), violations.toString());
        assertTrue(violations.getFirst().startsWith("key_type"), violations.toString());
    }
}
