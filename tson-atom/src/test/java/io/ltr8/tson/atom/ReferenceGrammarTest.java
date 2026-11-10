package io.ltr8.tson.atom;

import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.SiblingRef;
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
        assertTrue(ReferenceGrammar.parse("order").arguments().isEmpty());
        assertEquals(List.of("geo", "address"), ReferenceGrammar.parse("geo.address<T>").segments());
    }

    /** An argument is a reference, a qualified name or an application, nested ones included. */
    @Test
    void anArgumentIsAReference() {
        assertEquals(TypeRef.of("response", List.of(ref("order"), ref("geo.address"))),
                ReferenceGrammar.parse("response<order geo.address>"));
        assertEquals(TypeRef.of("box", List.of(TypeRef.of("pair", List.of(ref("text"), ref("int32"))))),
                ReferenceGrammar.parse("box<pair<text int32>>"));
    }

    /** A token that is no reference -- a number, a quoted token -- is no argument ([TSON-SCHEMA] §5.10). */
    @Test
    void aLiteralTokenIsNoArgument() {
        for (String text : List.of("maxint<10>", "response<order 201>", "p<-1>", "p<1box>", "box<\"a b\">",
                "box<pair<text \"a b\">>")) {
            assertThrows(AtomParseException.class, () -> ReferenceGrammar.parse(text), text);
        }
        AtomParseException refused = assertThrows(AtomParseException.class,
                () -> ReferenceGrammar.parse("maxint<10>"));
        assertTrue(refused.getMessage().contains("never a literal token"), refused.getMessage());
    }

    /** Identity is the parsed form: whitespace and a comma between arguments are free, and the text is one. */
    @Test
    void twoSpellingsAreOneReferenceWithOneCanonicalText() {
        TypeRef canonical = ReferenceGrammar.parse("map<text integer>");
        for (String spelling : List.of("map< text integer >", "map<text, integer>", "map<text\tinteger,>")) {
            assertEquals(canonical, ReferenceGrammar.parse(spelling), spelling);
            assertEquals("map<text integer>", ReferenceGrammar.parse(spelling).text(), spelling);
        }
    }

    @Test
    void aMalformedReferenceIsRefusedWithTheGrammarsReason() {
        for (String text : List.of("box<text", "box<>", "box<text>>", "box<<text>", ".box", "box..x", "box<text>x",
                "", "1box", "box<\"open>", "box <text>", "box<1x<y>>")) {
            assertThrows(AtomParseException.class, () -> ReferenceGrammar.parse(text), text);
        }
    }

    @Test
    void theNamesAReferenceWritesAreItsHeadAndItsReferenceArguments() {
        assertEquals(List.of("response", "box", "order"),
                ReferenceGrammar.names(ReferenceGrammar.parse("response<box<order>>")));
    }

    /** {@code kind!} reads to the sibling reference it spells, and writes back as the same text. */
    @Test
    void aSiblingReferenceRoundTrips() {
        Reference read = ReferenceGrammar.parseReference("kind!");
        assertEquals(SiblingRef.of("kind"), read);
        assertEquals("kind!", read.text());
        assertEquals(TypeRef.of("box", List.of(ref("text"))), ReferenceGrammar.parseReference("box<text>"));
    }

    /** Every position but a record field's type reads a type reference, so {@link ReferenceGrammar#parse} refuses one. */
    @Test
    void aTypeReferenceIsNoSiblingReference() {
        AtomParseException refused = assertThrows(AtomParseException.class, () -> ReferenceGrammar.parse("kind!"));
        assertTrue(refused.getMessage().contains("stands only at a record field's type"), refused.getMessage());
    }

    /** One identifier and the mark, ending the text; never an argument. */
    @Test
    void aMalformedOrMisplacedSiblingReferenceIsRefused() {
        for (String text : List.of("a.b!", "a!b", "kind! ", "kind!x", "!kind", "kind!!", "1x!", "box<kind!>",
                "box<text kind!>")) {
            assertThrows(AtomParseException.class, () -> ReferenceGrammar.parseReference(text), text);
        }
        AtomParseException argument = assertThrows(AtomParseException.class,
                () -> ReferenceGrammar.parseReference("box<kind!>"));
        assertTrue(argument.getMessage().contains("not at a template argument"), argument.getMessage());
    }
}
