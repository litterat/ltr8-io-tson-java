package io.ltr8.tson.atom;

import io.ltr8.tson.schema.meta.TypeRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A reference's text is a qualified name ([TSON-DATA] §7.7) and nothing else. */
class ReferenceGrammarTest {

    @Test
    void aReferenceIsAQualifiedName() {
        assertEquals(TypeRef.of("order"), ReferenceGrammar.parse("order"));
        assertEquals(TypeRef.of("orders.place_order"), ReferenceGrammar.parse("orders.place_order"));
        assertEquals(List.of("geo", "address"), ReferenceGrammar.parse("geo.address").segments());
    }

    /** An argument list is schema grammar ([TSON-SCHEMA] §4.2): text holding one is no name, saying how to write it. */
    @Test
    void anArgumentListIsNoPartOfAReference() {
        AtomParseException refused = assertThrows(AtomParseException.class,
                () -> ReferenceGrammar.parse("box<text>"));
        assertTrue(refused.getMessage().contains("an argument list is schema grammar"), refused.getMessage());
        assertTrue(refused.getMessage().contains("!meta.apply"), refused.getMessage());
    }

    @Test
    void textThatIsNoQualifiedNameIsRefused() {
        for (String text : List.of("box<text", "box<>", "box<text>>", "<text>", ".box", "box..x", "box.", "", "1box",
                "box <text>", "box text", "kind!", "\"box\"")) {
            assertThrows(AtomParseException.class, () -> ReferenceGrammar.parse(text), text);
        }
    }
}
