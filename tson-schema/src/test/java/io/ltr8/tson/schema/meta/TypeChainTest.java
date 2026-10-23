package io.ltr8.tson.schema.meta;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A type carries its own IS-A chain (SPEC-FEEDBACK.md #14): {@code type}'s one field on every type body, a record
 * template's included, the kind an entry's value gives it, judged by where a base kind is defined, and the constructor test.
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
        assertEquals(age, TypeChain.withoutChain(placed));
    }

    @Test
    void supertypesAreSortedAndWithoutRepeats() {
        assertEquals(List.of("atom", "top", "type"), TypeChain.of(List.of("type", "atom", "top", "atom")).supertypes());
    }

    @Test
    void anEntrysKindIsItsValuesAndARecordsIsTheBaseKindItsChainReaches() {
        RecordBody constructor = RecordBody.of(RecordBody.fieldMap()).withSupertypes(List.of("atom", "top", "type"));

        assertEquals(EntryKind.ATOM, EntryKind.of(constructor, n -> true));
        assertEquals(EntryKind.PRODUCT, EntryKind.of(RecordBody.of(RecordBody.fieldMap()), n -> true));
        assertEquals(EntryKind.ATOM, EntryKind.of(IntegerType.UNCONSTRAINED, n -> true));
        assertEquals(EntryKind.REFERENCE, EntryKind.of(new Reference(TypeRef.of("text")), n -> true));
    }

    @Test
    void aBaseKindIsJudgedByWhereItIsDefinedNeverBySpelling() {
        RecordBody payload = RecordBody.of(RecordBody.fieldMap()).withSupertypes(List.of("namespace"));

        assertEquals(EntryKind.NAMESPACE, EntryKind.of(payload, n -> true));
        assertEquals(EntryKind.PRODUCT, EntryKind.of(payload, n -> false), "a schema's own `namespace` is a record");
    }

    @Test
    void aConstructorIsWhatIsATheKernelsTopAndTheKernelsTopItself() {
        RecordBody constructor = RecordBody.of(RecordBody.fieldMap()).withSupertypes(List.of("atom", "top", "type"));
        RecordBody templated = RecordBody.of(RecordBody.fieldMap())
                .withParameters(List.of(new TemplateParam("T", TemplateParam.TYPE_KIND, Optional.empty())))
                .withSupertypes(List.of("namespace", "top"));

        assertTrue(EntryKind.isConstructor("integer_type", constructor, n -> true));
        assertTrue(EntryKind.isConstructor("op", templated, n -> true), "a template answers as its closed form");
        assertTrue(EntryKind.isConstructor("top", RecordBody.of(RecordBody.fieldMap()), n -> true));
        assertFalse(EntryKind.isConstructor("integer_type", constructor, n -> false), "a schema's own top");
        assertFalse(EntryKind.isConstructor("person", RecordBody.of(RecordBody.fieldMap()), n -> true));
    }

    @Test
    void aRecordTemplateCarriesAChainAsAnyTypeDoes() {
        RecordBody pet = RecordBody.of(RecordBody.fieldMap())
                .withParameters(List.of(new TemplateParam("T", TemplateParam.TYPE_KIND, Optional.empty())));
        RecordBody placed = pet.withChain(TypeChain.of(List.of("animal")).withParents(List.of(TypeRef.of("animal")))
                .withSubtypes(List.of("dog")));

        assertEquals(List.of("animal"), TypeChain.supertypesOf(placed));
        assertEquals(List.of(TypeRef.of("animal")), TypeChain.parentsOf(placed));
        assertEquals(List.of("dog"), TypeChain.subtypesOf(placed));
        assertEquals(placed.parameterNames(), pet.parameterNames());
    }
}
