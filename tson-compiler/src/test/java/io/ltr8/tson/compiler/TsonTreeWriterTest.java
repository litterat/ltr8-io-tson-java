package io.ltr8.tson.compiler;

import io.ltr8.tson.tree.ArrayNode;
import io.ltr8.tson.tree.AtomNode;
import io.ltr8.tson.tree.MissingNode;
import io.ltr8.tson.tree.RecordNode;
import io.ltr8.tson.tree.TsonNode;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TsonTreeWriter}: a {@link TsonNode} tree back to TSON text. Proves a hand-built tree writes to the
 * expected text, that a document reads then writes then reads back to an equal tree (value-preserving,
 * including a captured {@code !int32} width and built-in atom type-refs the object writer would drop), and
 * that a {@link MissingNode} can't be written.
 */
class TsonTreeWriterTest {

    /** Preserving: {@code !person} is scenery for the round trip, and a schemaless read reports an unlinked type-ref by default. */
    private static final TsonTreeReader READER = new TsonTreeReader().preservingUnknownTypeRefs();
    private static final TsonTreeWriter WRITER = new TsonTreeWriter();

    @Test
    void writesAHandBuiltRecordWithSeparationNotCommas() {
        Map<String, TsonNode> fields = new LinkedHashMap<>();
        fields.put("name", AtomNode.of("Ada"));
        fields.put("age", AtomNode.of(BigInteger.valueOf(30)));
        fields.put("skills", ArrayNode.of(AtomNode.of("a"), AtomNode.of("b")));
        RecordNode record = new RecordNode(fields, Optional.of("person"), java.util.List.of());

        assertEquals("!person { name: \"Ada\" age: 30 skills: [ \"a\" \"b\" ] }", WRITER.toTson(record));
    }

    @Test
    void emptyRecordWritesEmptyBraces() {
        assertEquals("{}", WRITER.toTson(RecordNode.of(Map.of())));
    }

    @Test
    void roundTripsAWholeDocumentToAnEqualTree() {
        String source = """
                !person {
                  name: "Ada"
                  age: 30
                  id: !uuid 9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09
                  joined: !date 1843-12-10
                  address: { city: "London" }
                  scores: [1 2 3]
                  active: true
                  note: null
                  nickname: _
                }
                """;

        TsonNode first = READER.read(source);
        TsonNode second = READER.read(WRITER.toTson(first));

        assertEquals(Optional.of("person"), second.typeRef());
        assertEquals(Optional.of("Ada"), second.at("/name").asString());
        assertEquals(BigInteger.valueOf(30), second.at("/age").asBigInteger().orElseThrow());
        assertEquals(UUID.fromString("9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09"),
                second.at("/id").as(UUID.class).orElseThrow());              // !uuid type-ref survived
        assertEquals(LocalDate.of(1843, 12, 10), second.at("/joined").as(LocalDate.class).orElseThrow());
        assertEquals(Optional.of("London"), second.at("/address/city").asString());
        assertEquals(BigInteger.valueOf(3), second.at("/scores/2").asBigInteger().orElseThrow());
        assertEquals(Boolean.TRUE, second.at("/active").asBoolean().orElseThrow());
        assertTrue(second.at("/note").isNull());
        assertTrue(second.at("/nickname").isAbsent());
    }

    @Test
    void preservesAnIntegerWidthTypeRefTheObjectWriterWouldDrop() {
        AtomNode typed = new AtomNode(42, Optional.of("int32"), java.util.List.of());
        assertEquals("!int32 42", WRITER.toTson(typed));
    }

    @Test
    void writingAMissingNodeThrows() {
        assertThrows(IllegalArgumentException.class, () -> WRITER.toTson(MissingNode.instance()));
    }
}
