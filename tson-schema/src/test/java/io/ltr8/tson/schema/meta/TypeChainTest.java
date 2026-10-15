package io.ltr8.tson.schema.meta;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * A type carries its own IS-A chain (SPEC-FEEDBACK.md #14): {@link Type}'s two slots on every body, the kind an
 * entry's value gives it, and a family base's members read from the chains that name it.
 */
class TypeChainTest {

    @Test
    void anAbsentChainIsAnEmptyOne() {
        IntegerType bound = new IntegerType(null, null, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        assertEquals(List.of(), bound.supertypes());
        assertEquals(List.of(), bound.subtypes());
        assertEquals(IntegerType.UNCONSTRAINED, bound);
    }

    @Test
    void withChainKeepsEveryFacetAndTheChainIsPartOfTheValue() {
        IntegerType age = IntegerType.ofMin(java.math.BigInteger.ZERO);
        IntegerType placed = age.withChain(List.of(TypeRef.of("integer")), List.of("adult_age"));

        assertEquals(age.min(), placed.min());
        assertEquals(List.of("integer"), placed.supertypeNames());
        assertEquals(List.of("adult_age"), placed.subtypes());
        assertNotEquals(age, placed);
        assertEquals(age, Type.withoutChain(placed));
    }

    @Test
    void anEntrysKindIsItsValuesAndARecordsIsTheBaseKindItsChainReaches() {
        RecordBody constructor = RecordBody.of(RecordBody.fieldMap()).withSupertypes(List.of("atom", "top", "type"));

        assertEquals(TypeKind.ATOM, TypeKind.of(constructor));
        assertEquals(TypeKind.PRODUCT, TypeKind.of(RecordBody.of(RecordBody.fieldMap())));
        assertEquals(TypeKind.ATOM, TypeKind.of(IntegerType.UNCONSTRAINED));
        assertEquals(TypeKind.REFERENCE, TypeKind.of(new Reference(TypeRef.of("text"))));
    }

    @Test
    void aFamilyBasesMembersAreTheEntriesWhoseChainNamesIt() {
        Map<String, Top> entries = new LinkedHashMap<>();
        entries.put("pet", new TemplateBody(List.of(new TemplateParam("T", TemplateParam.TYPE_REF,
                Optional.empty())), "!record { fields: {} }", Optional.of(RecordExtensionType.ABSTRACT)));
        entries.put("dog", RecordBody.of(RecordBody.fieldMap()).withSupertypes(List.of("pet")));
        entries.put("rock", RecordBody.of(RecordBody.fieldMap()));

        assertEquals(List.of("dog"), Type.subtypesOf("pet", entries.get("pet"), entries));
        assertEquals(List.of(), Type.subtypesOf("rock", entries.get("rock"), entries));
    }
}
