package io.ltr8.tson.tree;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        assertTrue(TsonAbsent.instance().isAbsent());
        assertTrue(TsonMissing.atField("nope").isMissing());
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
    void aMissingCarriesThePointerOfTheStepThatFailed() {
        TsonRecord person = sample();
        // the failing step, not the whole pointer asked for: address exists, city2 doesn't, /nope is never reached
        assertEquals(Optional.of("/address/city2"), person.at("/address/city2/nope").missingPath());
        assertEquals(Optional.of("/nope"), person.at("/nope/deeper").missingPath());
        assertEquals(Optional.of("/skills/99"), person.at("/skills/99").missingPath());
        // a non-integer token against an array fails at that token
        assertEquals(Optional.of("/skills/first"), person.at("/skills/first").missingPath());
        // a bare get is relative to its own receiver, the only frame a node has
        assertEquals(Optional.of("/city2"), person.get("address").get("city2").missingPath());
        // the first failure sticks: stepping on past it neither extends nor replaces the pointer
        TsonValue missing = person.at("/nope");
        assertEquals(Optional.of("/nope"), missing.get("deeper").get(0).at("/further").missingPath());
        // a present node has no missing path, whichever kind it is
        assertEquals(Optional.empty(), person.at("/name").missingPath());
        assertEquals(Optional.empty(), TsonAbsent.instance().missingPath());
    }

    @Test
    void missingPathsAreEscapedAndCompareByPath() {
        TsonRecord node = TsonRecord.of(Map.of("a", TsonAtom.of("x")));
        // a field name containing the pointer metacharacters comes back as a well-formed pointer
        assertEquals(Optional.of("/a~1b~0c"), node.get("a/b~c").missingPath());
        // equality is by where navigation died, so two chains failing at the same place agree
        assertEquals(node.get("zzz"), node.at("/zzz"));
        assertNotEquals(node.get("zzz"), node.get("yyy"));
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
    void numericConveniencesConvertExactlyOrGiveUp() {
        // the ceremony these replace: asNumber().orElseThrow().intValue()
        assertEquals(30, sample().get("age").asInt().orElseThrow());
        assertEquals(30L, sample().get("age").asLong().orElseThrow());
        assertEquals(30.0, sample().get("age").asDouble().orElseThrow());
        // an integral fractional part converts; a real one doesn't
        assertEquals(123, TsonAtom.of(new BigDecimal("123.0")).asInt().orElseThrow());
        assertEquals(23456, TsonAtom.of(new BigDecimal("234.56E2")).asInt().orElseThrow());
        assertEquals(OptionalInt.empty(), TsonAtom.of(new BigDecimal("345.6")).asInt());
        assertEquals(3, TsonAtom.of(3.0d).asInt().orElseThrow());
        assertEquals(OptionalInt.empty(), TsonAtom.of(0.1d).asInt());
        // out of range fails rather than wrapping or saturating -- long still holds what int can't
        TsonAtom big = TsonAtom.of(BigInteger.valueOf(Long.MAX_VALUE));
        assertEquals(OptionalInt.empty(), big.asInt());
        assertEquals(Long.MAX_VALUE, big.asLong().orElseThrow());
        assertEquals(OptionalLong.empty(), TsonAtom.of(BigInteger.TWO.pow(80)).asLong());
        // these convert where asBigInteger only casts: an int32 field holds an Integer
        TsonAtom narrowed = TsonAtom.of(7, "int32");
        assertEquals(7, narrowed.asInt().orElseThrow());
        assertEquals(Optional.empty(), narrowed.asBigInteger());
    }

    @Test
    void numericConveniencesRejectNonNumbersAndInfinity() {
        // "42" is a string per §4.4 -- text is never parsed back into a number
        assertEquals(OptionalInt.empty(), TsonAtom.of("42").asInt());
        assertEquals(OptionalDouble.empty(), TsonAtom.of("42").asDouble());
        assertEquals(OptionalInt.empty(), TsonAtom.of(true).asInt());
        // a non-atom, and a node that isn't there at all
        assertEquals(OptionalInt.empty(), sample().asInt());
        assertEquals(OptionalLong.empty(), sample().get("nope").asLong());
        assertEquals(OptionalDouble.empty(), TsonAbsent.instance().asDouble());
        // rounding to the nearest double is what a double accessor means...
        assertEquals(0.1d, TsonAtom.of(new BigDecimal("0.1")).asDouble().orElseThrow());
        assertEquals(0.1d, TsonAtom.of(0.1f).asDouble().orElseThrow());
        // ...but a magnitude that can't be finite yields empty, never Infinity
        assertEquals(OptionalDouble.empty(), TsonAtom.of(new BigDecimal("1E400")).asDouble());
    }

    @Test
    void absentAndMissingAreDistinct() {
        // "written, but holds no value" and "not in the tree at all" -- the two kinds the model keeps. A
        // read that produced no value lands on TsonAbsent whatever it was written as.
        assertTrue(TsonAbsent.instance().isAbsent());
        assertFalse(TsonAbsent.instance().isMissing());
        assertFalse(TsonMissing.atField("nope").isAbsent());
        assertTrue(TsonMissing.atField("nope").isMissing());
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

    /**
     * Load-bearing rather than cosmetic: a reader reporting on a decoded value stringifies whatever it
     * decoded, and in tree mode that is a {@link TsonAtom}. The record default would put {@code
     * TsonAtom[value=medium, typeRef=Optional[text], annotations=[]]} into a diagnostic's {@code
     * expected}/{@code actual} and into messages that interpolate a value.
     */
    @Test
    void atomRendersItsValueAloneRatherThanTheRecordsComponents() {
        assertEquals("medium", TsonAtom.of("medium", "text").toString());
        assertEquals("7", TsonAtom.of(BigInteger.valueOf(7)).toString());
        // Composites keep the record default -- rendering those as TSON text is TsonTreeWriter's job.
        assertTrue(TsonRecord.of(Map.of("a", TsonAtom.of(1))).toString().startsWith("TsonRecord["));
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
