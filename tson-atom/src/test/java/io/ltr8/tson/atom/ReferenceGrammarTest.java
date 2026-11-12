package io.ltr8.tson.atom;

import io.ltr8.tson.schema.meta.TypeRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reference grammar a reference-typed slot's value is read by ([TSON-DATA] §7.4): segments, an optional
 * argument list of references, and one canonical text.
 */
class ReferenceGrammarTest {

    private static TypeRef ref(String name) {
        return TypeRef.of(name);
    }

    @Test
    void aNameIsAReferenceWithNoArguments() {
        assertEquals(TypeRef.of("order"), ReferenceGrammar.parse("order"));
        assertEquals(TypeRef.of("orders.place_order"), ReferenceGrammar.parse("orders.place_order"));
        assertEquals(List.of("geo", "address"), ReferenceGrammar.parse("geo.address").segments());
    }

    /** The grammar spells an argument list, each argument a reference, nested ones included. */
    @Test
    void anArgumentIsAReference() {
        assertEquals(new ReferenceGrammar.Spelled("response", List.of(spelled("order"), spelled("geo.address"))),
                ReferenceGrammar.spelled("response<order geo.address>"));
        assertEquals(new ReferenceGrammar.Spelled("box", List.of(new ReferenceGrammar.Spelled("pair",
                List.of(spelled("text"), spelled("int32"))))), ReferenceGrammar.spelled("box<pair<text int32>>"));
    }

    /** A reference is a name ([TSON-SCHEMA] §4.2): one spelling an application is refused, saying how to write it. */
    @Test
    void anApplicationIsNoReference() {
        AtomValidationException refused = assertThrows(AtomValidationException.class,
                () -> ReferenceGrammar.parse("box<text>"));
        assertTrue(refused.getMessage().contains("a reference is a name"), refused.getMessage());
        assertTrue(refused.getMessage().contains("!meta.apply"), refused.getMessage());
    }

    private static ReferenceGrammar.Spelled spelled(String name) {
        return new ReferenceGrammar.Spelled(name, List.of());
    }

    /** A token that is no reference -- a number, a quoted token -- is no argument ([TSON-SCHEMA] §5.10). */
    @Test
    void aLiteralTokenIsNoArgument() {
        for (String text : List.of("maxint<10>", "response<order 201>", "p<-1>", "p<1box>", "box<\"a b\">",
                "box<pair<text \"a b\">>")) {
            assertThrows(AtomParseException.class, () -> ReferenceGrammar.spelled(text), text);
        }
        AtomParseException refused = assertThrows(AtomParseException.class,
                () -> ReferenceGrammar.spelled("maxint<10>"));
        assertTrue(refused.getMessage().contains("never a literal token"), refused.getMessage());
    }

    /** Identity is the parsed form: whitespace and a comma between arguments are free, and the text is one. */
    @Test
    void twoSpellingsAreOneReferenceWithOneCanonicalText() {
        ReferenceGrammar.Spelled canonical = ReferenceGrammar.spelled("map<text integer>");
        for (String spelling : List.of("map< text integer >", "map<text, integer>", "map<text\tinteger,>")) {
            assertEquals(canonical, ReferenceGrammar.spelled(spelling), spelling);
            assertEquals("map<text integer>", ReferenceGrammar.spelled(spelling).text(), spelling);
        }
    }

    @Test
    void aMalformedReferenceIsRefusedWithTheGrammarsReason() {
        for (String text : List.of("box<text", "box<>", "box<text>>", "box<<text>", ".box", "box..x", "box<text>x",
                "", "1box", "box<\"open>", "box <text>", "box<1x<y>>")) {
            assertThrows(AtomParseException.class, () -> ReferenceGrammar.spelled(text), text);
        }
    }
}
