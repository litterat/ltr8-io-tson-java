package io.ltr8.tson.compiler.tree;

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
 * The {@link TsonNode} model and its query API: kind tests, never-throwing navigation ({@link
 * TsonNode#get}/{@link TsonNode#at} yielding {@link MissingNode}), typed accessors, immutability, and RFC 6901
 * pointer parsing.
 */
class TsonNodeTest {

    /** A small person-ish tree: {@code { name: "Ada"  age: !int32 30  skills: ["a" "b"]  address: { city: "London" } }}. */
    private static RecordNode sample() {
        Map<String, TsonNode> fields = new LinkedHashMap<>();
        fields.put("name", AtomNode.of("Ada"));
        fields.put("age", AtomNode.of(BigInteger.valueOf(30), "int32"));
        fields.put("skills", ArrayNode.of(AtomNode.of("a"), AtomNode.of("b")));
        fields.put("address", RecordNode.of(Map.of("city", AtomNode.of("London"))));
        return RecordNode.of(fields);
    }

    @Test
    void kindTestsAreExclusiveAndCorrect() {
        assertTrue(sample().isRecord());
        assertTrue(sample().isContainer());
        assertFalse(sample().isAtom());
        assertTrue(ArrayNode.of().isArray());
        assertTrue(TupleNode.of().isTuple());
        assertTrue(AtomNode.of("x").isAtom());
        assertTrue(NullNode.instance().isNull());
        assertTrue(AbsentNode.instance().isAbsent());
        assertTrue(MissingNode.instance().isMissing());
        // array and tuple are structurally alike but distinct kinds
        assertFalse(TupleNode.of().isArray());
        assertFalse(ArrayNode.of().isTuple());
    }

    @Test
    void getByNameAndIndexNavigatesAndTypedAccessorsRead() {
        RecordNode person = sample();
        assertEquals(Optional.of("Ada"), person.get("name").asString());
        assertEquals(Optional.of(BigInteger.valueOf(30)), person.get("age").asBigInteger());
        assertEquals(Optional.of("int32"), person.get("age").typeRef());
        assertEquals(Optional.of("b"), person.get("skills").get(1).asString());
        assertEquals(Optional.of("London"), person.get("address").get("city").asString());
    }

    @Test
    void navigationNeverThrowsAndYieldsMissing() {
        RecordNode person = sample();
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
        RecordNode person = sample();
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
        Map<String, TsonNode> fields = new LinkedHashMap<>();
        fields.put("a/b", AtomNode.of("slash"));
        fields.put("m~n", AtomNode.of("tilde"));
        fields.put("~1", AtomNode.of("literal-tilde-one"));
        RecordNode node = RecordNode.of(fields);
        assertEquals(Optional.of("slash"), node.at("/a~1b").asString());
        assertEquals(Optional.of("tilde"), node.at("/m~0n").asString());
        assertEquals(Optional.of("literal-tilde-one"), node.at("/~01").asString());
    }

    @Test
    void atomTypedAccessorsAndGenericAs() {
        UUID id = UUID.randomUUID();
        AtomNode node = AtomNode.of(id, "uuid");
        assertEquals(Optional.of(id), node.as(UUID.class));
        assertEquals(Optional.of("uuid"), node.typeRef());
        assertEquals(Optional.empty(), node.asString());
        assertEquals(Optional.of(BigInteger.TEN), AtomNode.of(BigInteger.TEN).asNumber().map(n -> BigInteger.valueOf(n.longValue())));
        assertTrue(AtomNode.of(true).asBoolean().orElseThrow());
    }

    @Test
    void nullAbsentAndMissingAreDistinct() {
        assertTrue(NullNode.instance().isNull());
        assertFalse(NullNode.instance().isAbsent());
        assertFalse(NullNode.instance().isMissing());
        assertFalse(AbsentNode.instance().isNull());
        assertTrue(AbsentNode.instance().isAbsent());
        assertFalse(MissingNode.instance().isNull());
        assertFalse(MissingNode.instance().isAbsent());
        assertTrue(MissingNode.instance().isMissing());
    }

    @Test
    void nodesAreImmutableValueTypes() {
        // records give value equality
        assertEquals(AtomNode.of("x"), AtomNode.of("x"));
        assertEquals(sample(), sample());
        // the fields map is defensively copied and unmodifiable
        Map<String, TsonNode> source = new LinkedHashMap<>();
        source.put("a", AtomNode.of(1));
        RecordNode node = RecordNode.of(source);
        source.put("b", AtomNode.of(2));                       // mutating the source must not leak in
        assertTrue(node.get("b").isMissing());
        assertThrows(UnsupportedOperationException.class, () -> node.fields().put("c", AtomNode.of(3)));
        // an AtomNode value must not be null
        assertThrows(NullPointerException.class, () -> AtomNode.of(null));
    }

    @Test
    void mapNodeKeysAreNodesAndGetByStringMatches() {
        MapNode map = MapNode.of(List.of(
                new MapNode.Entry(AtomNode.of("one"), AtomNode.of(BigInteger.ONE)),
                new MapNode.Entry(AtomNode.of("two"), AtomNode.of(BigInteger.TWO))));
        assertTrue(map.isMap());
        assertEquals(Optional.of(BigInteger.TWO), map.get("two").asBigInteger());
        assertTrue(map.get("three").isMissing());
        assertInstanceOf(AtomNode.class, map.entries().get(0).key());
    }
}
