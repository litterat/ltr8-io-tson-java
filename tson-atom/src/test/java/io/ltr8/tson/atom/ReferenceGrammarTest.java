package io.ltr8.tson.atom;

import io.ltr8.tson.schema.meta.TypeRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A reference's text is a qualified name, split at {@code .} into segments ([TSON-SCHEMA] §5.12), and nothing else. */
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

    /**
     * A value's name splits at {@code .} before any family is consulted ([TSON-SCHEMA] §5.12): its first segment is an
     * identifier, and each further one is the family's of what it enters, which the walk judges.
     */
    @Test
    void aValuesNameSplitsBeforeAnyFamilyIsConsulted() {
        assertEquals(List.of("main", "public api", "ping"),
                ReferenceGrammar.valueName("main.public api.ping").segments());
        for (String text : List.of("", ".main", "main.", "main..ping", "ma in.ping", "1x.ping", "box<text>")) {
            assertThrows(AtomParseException.class, () -> ReferenceGrammar.valueName(text), text);
        }
        assertThrows(AtomParseException.class, () -> ReferenceGrammar.parse("main.public api.ping"));
    }

    /** One unquoted token exactly when every segment is an identifier, which is when a writer leaves it unquoted. */
    @Test
    void aQualifiedNameIsOneUnquotedToken() {
        assertTrue(ReferenceGrammar.isQualifiedName("orders.place_order"));
        assertFalse(ReferenceGrammar.isQualifiedName("main.public api.ping"));
        assertFalse(ReferenceGrammar.isQualifiedName(""));
    }

    @Test
    void aDecomposedSpellingIsTheNameItsComposedOneIs() {
        assertEquals(TypeRef.of("caf\u00e9.plain"), ReferenceGrammar.valueName("cafe\u0301.plain"));
        assertEquals(TypeRef.of("caf\u00e9"), ReferenceGrammar.parse("cafe\u0301"));
    }

    @Test
    void textThatIsNoQualifiedNameIsRefused() {
        for (String text : List.of("box<text", "box<>", "box<text>>", "<text>", ".box", "box..x", "box.", "", "1box",
                "box <text>", "box text", "kind!", "\"box\"")) {
            assertThrows(AtomParseException.class, () -> ReferenceGrammar.parse(text), text);
        }
    }
}
