package io.ltr8.tson.schema.meta;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A type carries its own IS-A chain (SPEC-FEEDBACK.md #14): {@code chained}'s one record on every body and on a
 * template, the kind an entry's value gives it, judged by where a base kind is defined, and the constructor test.
 */
class TypeChainTest {

    @Test
    void anAbsentChainIsAnEmptyOne() {
        IntegerType bound = new IntegerType(null, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        assertEquals(TypeChain.EMPTY, bound.chain());
        assertEquals(List.of(), bound.supertypes());
        assertEquals(IntegerType.UNCONSTRAINED, bound);
    }

    @Test
    void withChainKeepsEveryFacetAndTheChainIsPartOfTheValue() {
        IntegerType age = IntegerType.ofMin(java.math.BigInteger.ZERO);
        IntegerType placed = age.withChain(TypeChain.of(List.of("integer")).withSubtypes(List.of("adult_age")));

        assertEquals(age.min(), placed.min());
        assertEquals(List.of("integer"), placed.supertypes());
        assertEquals(List.of("adult_age"), placed.subtypes());
        assertNotEquals(age, placed);
        assertEquals(age, Chained.withoutChain(placed));
    }

    @Test
    void supertypesAreSortedAndWithoutRepeats() {
        assertEquals(List.of("atom", "top", "type"), TypeChain.of(List.of("type", "atom", "top", "atom")).supertypes());
    }

    @Test
    void anEntrysKindIsItsValuesAndARecordsIsTheBaseKindItsChainReaches() {
        RecordBody constructor = RecordBody.of(RecordBody.fieldMap()).withSupertypes(List.of("atom", "top", "type"));

        assertEquals(TypeKind.ATOM, TypeKind.of(constructor, n -> true));
        assertEquals(TypeKind.PRODUCT, TypeKind.of(RecordBody.of(RecordBody.fieldMap()), n -> true));
        assertEquals(TypeKind.ATOM, TypeKind.of(IntegerType.UNCONSTRAINED, n -> true));
        assertEquals(TypeKind.REFERENCE, TypeKind.of(new Reference(TypeRef.of("text")), n -> true));
    }

    @Test
    void aBaseKindIsJudgedByWhereItIsDefinedNeverBySpelling() {
        RecordBody payload = RecordBody.of(RecordBody.fieldMap()).withSupertypes(List.of("data"));

        assertEquals(TypeKind.DATA, TypeKind.of(payload, n -> true));
        assertEquals(TypeKind.PRODUCT, TypeKind.of(payload, n -> false), "a schema's own `data` is a record");
    }

    @Test
    void aConstructorIsWhatIsATheKernelsTopAndTheKernelsTopItself() {
        RecordBody constructor = RecordBody.of(RecordBody.fieldMap()).withSupertypes(List.of("atom", "top", "type"));
        TemplateBody templated = new TemplateBody(List.of(new TemplateParam("T", TemplateParam.TYPE_REF,
                Optional.empty())), "!record { fields: {} }", Optional.of(RecordExtensionType.ABSTRACT))
                .withSupertypes(List.of("data", "top"));

        assertTrue(TypeKind.isConstructor("integer_type", constructor, n -> true));
        assertTrue(TypeKind.isConstructor("op", templated, n -> true), "a template answers as its closed form");
        assertTrue(TypeKind.isConstructor("top", RecordBody.of(RecordBody.fieldMap()), n -> true));
        assertFalse(TypeKind.isConstructor("integer_type", constructor, n -> false), "a schema's own top");
        assertFalse(TypeKind.isConstructor("person", RecordBody.of(RecordBody.fieldMap()), n -> true));
    }

    @Test
    void aTemplateCarriesAChainAsAnyTypeDoes() {
        TemplateBody pet = new TemplateBody(List.of(new TemplateParam("T", TemplateParam.TYPE_REF,
                Optional.empty())), "!record { fields: {} }", Optional.of(RecordExtensionType.ABSTRACT));
        TemplateBody placed = pet.withChain(TypeChain.of(List.of("animal")).withParents(List.of(TypeRef.of("animal")))
                .withSubtypes(List.of("dog")));

        assertEquals(List.of("animal"), Chained.supertypesOf(placed));
        assertEquals(List.of(TypeRef.of("animal")), Chained.parentsOf(placed));
        assertEquals(List.of("dog"), Chained.subtypesOf(placed));
        assertEquals(placed.parameterNames(), pet.parameterNames());
    }
}
