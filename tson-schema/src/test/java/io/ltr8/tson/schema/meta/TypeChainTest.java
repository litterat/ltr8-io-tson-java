package io.ltr8.tson.schema.meta;

import io.ltr8.tson.schema.numeric.IntegerType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A type carries its own IS-A chain ([TSON-SCHEMA] §4.1): {@code type}'s one field on every type body, a record
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
        RecordBody constructor = RecordBody.of(RecordBody.fieldMap())
                .withSupertypes(List.of("meta.atom", "meta.top", "meta.type"));

        assertEquals(EntryKind.ATOM, EntryKind.of(constructor, n -> true));
        assertEquals(EntryKind.PRODUCT, EntryKind.of(RecordBody.of(RecordBody.fieldMap()), n -> true));
        assertEquals(EntryKind.ATOM, EntryKind.of(IntegerType.UNCONSTRAINED, n -> true));
        assertEquals(EntryKind.ALIAS, EntryKind.of(new Alias(TypeRef.of("text")), n -> true));
    }

    @Test
    void aBaseKindIsJudgedByWhereItIsDefinedNeverBySpelling() {
        RecordBody payload = RecordBody.of(RecordBody.fieldMap()).withSupertypes(List.of("meta.atom"));

        assertEquals(EntryKind.ATOM, EntryKind.of(payload, n -> true));
        assertEquals(EntryKind.PRODUCT, EntryKind.of(payload, n -> false), "a schema's own `meta.atom` is a record");
        RecordBody bare = RecordBody.of(RecordBody.fieldMap()).withSupertypes(List.of("atom"));
        assertEquals(EntryKind.PRODUCT, EntryKind.of(bare, n -> true), "a schema's own `atom` is no kernel name");
    }

    @Test
    void aConstructorIsWhatIsATheKernelsTopAndTheKernelsTopItself() {
        RecordBody constructor = RecordBody.of(RecordBody.fieldMap())
                .withSupertypes(List.of("meta.atom", "meta.top", "meta.type"));
        RecordBody templated = emptyTemplate(List.of(new Param("T")))
                .withSupertypes(List.of("meta.namespace", "meta.top"));

        assertTrue(EntryKind.isConstructor("numeric.integer_type", constructor, n -> true));
        assertTrue(EntryKind.isConstructor("op", templated, n -> true), "a template answers as its closed form");
        assertTrue(EntryKind.isConstructor("meta.top", RecordBody.of(RecordBody.fieldMap()), n -> true));
        assertFalse(EntryKind.isConstructor("numeric.integer_type", constructor, n -> false), "a schema's own top");
        assertFalse(EntryKind.isConstructor("person", RecordBody.of(RecordBody.fieldMap()), n -> true));
    }

    @Test
    void anOpenRecordCarriesAChainAsAnyTypeDoes() {
        RecordBody pet = emptyTemplate(List.of(new Param("T")));
        RecordBody placed = pet.withChain(TypeChain.of(List.of("animal")).withParents(List.of(TypeRef.of("animal")))
                .withSubtypes(List.of("dog")));

        assertEquals(List.of("animal"), TypeChain.supertypesOf(placed));
        assertEquals(List.of(TypeRef.of("animal")), TypeChain.parentsOf(placed));
        assertEquals(List.of("dog"), TypeChain.subtypesOf(placed));
        assertEquals(placed.parameterNames(), pet.parameterNames());
    }

    @Test
    void anOpenRecordHoldsItsParametersAsParameterReferences() {
        RecordBody open = new RecordBody(TypeChain.EMPTY.withParents(List.of(TypeRef.of("base",
                List.of(TypeRef.of("T"))))), List.of(new Param("T")), RecordBody.fieldMap(
                Map.entry("v", RecordField.required(TypeRef.of("T"))),
                Map.entry("w", RecordField.required(TypeRef.of("box", List.of(TypeRef.of("T"))))),
                Map.entry("x", RecordField.required(TypeRef.of("text")))),
                List.of(), RecordExtensionType.OPEN, List.of(), Optional.empty());

        assertTrue(open.isOpen());
        assertEquals(ParamRef.of("T"), open.fields().get("v").type());
        assertEquals(TypeRef.of("box", List.of(ParamRef.of("T"))), open.fields().get("w").type());
        assertEquals(TypeRef.of("text"), open.fields().get("x").type());
        assertEquals(List.of(TypeRef.of("base", List.of(ParamRef.of("T")))), open.parents());
    }

    /**
     * Inside a type written in place an open record's parameter is scoped at every slot, and one it does not declare is
     * refused there too; a closed record judges none, since one in place holds its open record's.
     */
    @Test
    void anOpenRecordScopesItsParametersInsideATypeWrittenInPlace() {
        TypeInline inPlace = new TypeInline(RecordBody.of(RecordBody.fieldMap(
                Map.entry("cursor", RecordField.required(TypeRef.of("T"))),
                Map.entry("all", RecordField.required(new TypeInline(ArrayBody.of(TypeRef.of("T"))))))));
        RecordBody open = new RecordBody(TypeChain.EMPTY, List.of(new Param("T")), RecordBody.fieldMap(
                Map.entry("meta", RecordField.required(inPlace))), List.of(), RecordExtensionType.OPEN, List.of(),
                Optional.empty());

        RecordBody meta = (RecordBody) ((TypeInline) open.fields().get("meta").type()).of();
        assertEquals(ParamRef.of("T"), meta.fields().get("cursor").type());
        assertEquals(new TypeInline(ArrayBody.of(ParamRef.of("T"))), meta.fields().get("all").type());
        assertThrows(IllegalArgumentException.class, () -> new RecordBody(TypeChain.EMPTY, List.of(new Param("U")),
                RecordBody.fieldMap(Map.entry("meta", RecordField.required(new TypeInline(meta)))), List.of(),
                RecordExtensionType.OPEN, List.of(), Optional.empty()), "T is not U's");
    }

    private static RecordBody emptyTemplate(List<Param> parameters) {
        return new RecordBody(TypeChain.EMPTY, parameters, new io.ltr8.annotation.AnnotatedMap<>(), List.of(),
                RecordExtensionType.OPEN, List.of(), Optional.empty());
    }
}
