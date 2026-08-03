package io.ltr8.tson.compiler;

import io.ltr8.tson.tree.TsonNode;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The schemaless {@link TsonTreeReader}: TSON text straight to a {@link TsonNode} tree with no schema,
 * structure and types coming from the wire. Proves records stay records and arrays stay arrays (no schema
 * to distinguish tuple), leaves are base-resolved (or built-in-typed when tagged), wire type-refs are
 * captured, and null/absent/empty-brace map to the right kinds.
 */
class TsonTreeReaderTest {

    private static final TsonTreeReader READER = new TsonTreeReader();

    @Test
    void readsRecordsMapsArraysAndTypedLeavesWithNoSchema() {
        TsonNode node = READER.read("""
                !person {
                  name: "Ada"
                  age: 30
                  id: !uuid 9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09
                  joined: !date 1843-12-10
                  address: { city: "London" }
                  skills: ["a" "b"]
                  active: true
                  note: null
                  nickname: _
                }
                """);

        assertTrue(node.isRecord());
        assertEquals(Optional.of("person"), node.typeRef());                          // wire type-ref captured
        assertEquals(Optional.of("Ada"), node.get("name").asString());
        assertEquals(BigInteger.valueOf(30), node.get("age").asBigInteger().orElseThrow()); // schemaless -> BigInteger
        assertEquals(Optional.of("uuid"), node.get("id").typeRef());
        assertTrue(node.get("id").as(UUID.class).isPresent());                        // built-in atom typing
        assertEquals(LocalDate.of(1843, 12, 10), node.at("/joined").as(LocalDate.class).orElseThrow());
        assertTrue(node.get("address").isRecord());                                   // nested record stays a record
        assertEquals(Optional.of("London"), node.at("/address/city").asString());
        assertTrue(node.get("skills").isArray());                                     // array, never tuple (schemaless)
        assertEquals(Optional.of("b"), node.at("/skills/1").asString());
        assertEquals(Boolean.TRUE, node.get("active").asBoolean().orElseThrow());
        assertTrue(node.get("note").isNull());                                        // the null token
        assertTrue(node.get("nickname").isAbsent());                                  // the _ sentinel
    }

    @Test
    void emptyBraceReadsAsAnEmptyRecord() {
        TsonNode node = READER.read("{}");
        assertTrue(node.isRecord());
        assertTrue(node.get("anything").isMissing());
    }

    @Test
    void readsAMapWithTypedKeys() {
        TsonNode node = READER.read("{ \"a\" => 1  \"b\" => 2 }");
        assertTrue(node.isMap());
        assertEquals(BigInteger.ONE, node.get("a").asBigInteger().orElseThrow());
        assertEquals(BigInteger.TWO, node.get("b").asBigInteger().orElseThrow());
    }

    @Test
    void readsARootAtom() {
        assertEquals(BigInteger.valueOf(42), READER.read("42").asBigInteger().orElseThrow());
        assertEquals(Optional.of("hi"), READER.read("\"hi\"").asString());
    }

    @Test
    void malformedInputThrows() {
        assertThrows(RuntimeException.class, () -> READER.read("{ unterminated"));
    }
}
