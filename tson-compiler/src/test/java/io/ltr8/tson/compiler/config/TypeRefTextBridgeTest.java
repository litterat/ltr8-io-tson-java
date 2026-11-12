package io.ltr8.tson.compiler.config;

import io.ltr8.tson.atom.AtomParseException;
import io.ltr8.tson.schema.meta.TypeRef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A reference bound from its text, a JSON string's among them. */
class TypeRefTextBridgeTest {

    @Test
    void aReferenceIsReadFromItsTextAndAMarkedNameIsNone() {
        assertEquals(TypeRef.of("text"), new TypeRefTextBridge().toObject("text"));
        assertThrows(AtomParseException.class, () -> new TypeRefTextBridge().toObject("box<text>"));
        assertThrows(AtomParseException.class, () -> new TypeRefTextBridge().toObject("kind!"));
        assertThrows(AtomParseException.class, () -> new TypeRefTextBridge.ForName().toObject("kind!"));
    }
}
