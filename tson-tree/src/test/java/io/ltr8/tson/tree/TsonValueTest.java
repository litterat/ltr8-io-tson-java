package io.ltr8.tson.tree;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link TsonValue} model and its query API: kind tests, never-throwing navigation ({@link
 * TsonValue#get}/{@link TsonValue#at} yielding {@link TsonMissing}), typed accessors, immutability, and RFC 6901
 * pointer parsing.
 */
class TsonValueTest {

    /** A small person-ish tree: {@code { name: "Ada"  age: !int32 30  skills: ["a" "b"]  address: { city: "London" } }}. */
    private static TsonRecord sample() {
        Map<String, TsonValue> fields = new LinkedHashMap<>();
        fields.put("name", TsonAtom.of("Ada"));
        fields.put("age", TsonAtom.of(BigInteger.valueOf(30), "int32"));
        fields.put("skills", TsonArray.of(TsonAtom.of("a"), TsonAtom.of("b")));
        fields.put("address", TsonRecord.of(Map.of("city", TsonAtom.of("London"))));
        return TsonRecord.of(fields);
    }

    @Test
    void kindTestsAreExclusiveAndCorrect() {
        assertTrue(sample().isRecord());
        assertTrue(sample().isContainer());
        assertFalse(sample().isAtom());
        assertTrue(TsonArray.of().isArray());
        assertTrue(TsonTuple.of().isTuple());
        assertTrue(TsonAtom.of("x").isAtom());
        assertTrue(TsonNull.instance().isNull());
        assertTrue(TsonAbsent.instance().isAbsent());
        assertTrue(TsonMissing.instance().isMissing());
        // array and tuple are structurally alike but distinct kinds
        assertFalse(TsonTuple.of().isArray());
        assertFalse(TsonArray.of().isTuple());
    }

    @Test
    void getByNameAndIndexNavigatesAndTypedAccessorsRead() {
        TsonRecord person = sample();
        assertEquals(Optional.of("Ada"), person.get("name").asString());
        assertEquals(Optional.of(BigInteger.valueOf(30)), person.get("age").asBigInteger());
        assertEquals(Optional.of("int32"), person.get("age").typeRef());
        assertEquals(Optional.of("b"), person.get("skills").get(1).asString());
        assertEquals(Optional.of("London"), person.get("address").get("city").asString());
    }

    @Test
    void navigationNeverThrowsAndYieldsMissing() {
        TsonRecord person = sample();
        assertTrue(person.get("nope").isMissing());
        assertTrue(person.get("skills").get(99).isMissing());
        // a deep chain through absent nodes stays Missing, and typed accessors are empty
        assertTrue(person.get("nope").get("deeper").get(0).isMissing());
        assertEquals(Optional.empty(), person.get("nope").asString());
        // a non-atom yields empty for a value accessor rather than throwing
        assertEquals(Optional.empty(), person.get("skills").asString());
    }

    @Test
    void atResolvesRfc6901Pointers() {
        TsonRecord person = sample();
        assertEquals(person, person.at(""));
        assertEquals(Optional.of("Ada"), person.at("/name").asString());
        assertEquals(Optional.of("b"), person.at("/skills/1").asString());
        assertEquals(Optional.of("London"), person.at("/address/city").asString());
        assertTrue(person.at("/skills/99").isMissing());
        assertTrue(person.at("/no/such/path").isMissing());
        assertThrows(IllegalArgumentException.class, () -> person.at("name"));
    }

    @Test
    void atUnescapesTildeSequences() {
        // "a/b" and "m~n" as field names, escaped per RFC 6901 as ~1 and ~0 (and ~01 decodes to ~1, not /)
        Map<String, TsonValue> fields = new LinkedHashMap<>();
        fields.put("a/b", TsonAtom.of("slash"));
        fields.put("m~n", TsonAtom.of("tilde"));
        fields.put("~1", TsonAtom.of("literal-tilde-one"));
        TsonRecord node = TsonRecord.of(fields);
        assertEquals(Optional.of("slash"), node.at("/a~1b").asString());
        assertEquals(Optional.of("tilde"), node.at("/m~0n").asString());
        assertEquals(Optional.of("literal-tilde-one"), node.at("/~01").asString());
    }

    @Test
    void atomTypedAccessorsAndGenericAs() {
        UUID id = UUID.randomUUID();
        TsonAtom node = TsonAtom.of(id, "uuid");
        assertEquals(Optional.of(id), node.as(UUID.class));
        assertEquals(Optional.of("uuid"), node.typeRef());
        assertEquals(Optional.empty(), node.asString());
        assertEquals(Optional.of(BigInteger.TEN), TsonAtom.of(BigInteger.TEN).asNumber().map(n -> BigInteger.valueOf(n.longValue())));
        assertTrue(TsonAtom.of(true).asBoolean().orElseThrow());
    }

    @Test
    void nullAbsentAndMissingAreDistinct() {
        assertTrue(TsonNull.instance().isNull());
        assertFalse(TsonNull.instance().isAbsent());
        assertFalse(TsonNull.instance().isMissing());
        assertFalse(TsonAbsent.instance().isNull());
        assertTrue(TsonAbsent.instance().isAbsent());
        assertFalse(TsonMissing.instance().isNull());
        assertFalse(TsonMissing.instance().isAbsent());
        assertTrue(TsonMissing.instance().isMissing());
    }

    @Test
    void nodesAreImmutableValueTypes() {
        // records give value equality
        assertEquals(TsonAtom.of("x"), TsonAtom.of("x"));
        assertEquals(sample(), sample());
        // the fields map is defensively copied and unmodifiable
        Map<String, TsonValue> source = new LinkedHashMap<>();
        source.put("a", TsonAtom.of(1));
        TsonRecord node = TsonRecord.of(source);
        source.put("b", TsonAtom.of(2));                       // mutating the source must not leak in
        assertTrue(node.get("b").isMissing());
        assertThrows(UnsupportedOperationException.class, () -> node.fields().put("c", TsonAtom.of(3)));
        // a TsonAtom value must not be null
        assertThrows(NullPointerException.class, () -> TsonAtom.of(null));
    }

    @Test
    void mapNodeKeysAreNodesAndGetByStringMatches() {
        TsonMap map = TsonMap.of(List.of(
                new TsonMap.Entry(TsonAtom.of("one"), TsonAtom.of(BigInteger.ONE)),
                new TsonMap.Entry(TsonAtom.of("two"), TsonAtom.of(BigInteger.TWO))));
        assertTrue(map.isMap());
        assertEquals(Optional.of(BigInteger.TWO), map.get("two").asBigInteger());
        assertTrue(map.get("three").isMissing());
        assertInstanceOf(TsonAtom.class, map.entries().get(0).key());
    }
}
