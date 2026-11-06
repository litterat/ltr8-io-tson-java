package io.ltr8.tson.compiler.config;

import io.ltr8.tson.atom.AtomParseException;
import io.ltr8.tson.schema.meta.SiblingRef;
import io.ltr8.tson.schema.meta.TypeRef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A reference bound from its text -- a JSON string's among them: a {@code Reference} or {@code SiblingRef} host reads
 * {@code kind!}, and a {@code TypeRef} host refuses it by the position rule ([TSON-SCHEMA] §5.2).
 */
class TypeRefTextBridgeTest {

    @Test
    void aSiblingReferenceIsReadFromItsText() {
        assertEquals(SiblingRef.of("kind"), new TypeRefTextBridge.ForReference().toObject("kind!"));
        assertEquals(SiblingRef.of("kind"), new TypeRefTextBridge.ForSibling().toObject("kind!"));
        assertEquals("kind!", new TypeRefTextBridge.ForSibling().toData(SiblingRef.of("kind")));
        assertEquals(TypeRef.of("text"), new TypeRefTextBridge.ForReference().toObject("text"));
    }

    @Test
    void aTypeReferenceHostRefusesASibling() {
        assertThrows(AtomParseException.class, () -> new TypeRefTextBridge().toObject("kind!"));
        assertThrows(AtomParseException.class, () -> new TypeRefTextBridge.ForName().toObject("kind!"));
        assertThrows(AtomParseException.class, () -> new TypeRefTextBridge.ForSibling().toObject("kind"));
    }
}
