package io.ltr8.tson.atom;

import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.SiblingRef;
import io.ltr8.tson.schema.meta.Token;
import io.ltr8.tson.schema.meta.TypeArgument;
import io.ltr8.tson.schema.meta.TypeRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reference grammar a reference-typed slot's value is read by ([TSON-DATA] §7.4): segments, an optional
 * argument list of references and tokens, and one canonical text.
 */
class ReferenceGrammarTest {

    private static TypeArgument.Ref ref(String name) {
        return new TypeArgument.Ref(TypeRef.of(name));
    }

    private static TypeArgument.Value value(String text, Token.Form form) {
        return new TypeArgument.Value(new Token(text, form));
    }

    @Test
    void aNameIsAReferenceWithNoArguments() {
        assertEquals(TypeRef.of("order"), ReferenceGrammar.parse("order"));
        assertEquals(TypeRef.of("orders.place_order"), ReferenceGrammar.parse("orders.place_order"));
        assertTrue(ReferenceGrammar.parse("order").arguments().isEmpty());
        assertEquals(List.of("geo", "address"), ReferenceGrammar.parse("geo.address<T>").segments());
    }

    /** A qualified name is a reference, nested ones included; any other token, a number or a quoted one, a value. */
    @Test
    void anArgumentIsAReferenceOrAToken() {
        assertEquals(TypeRef.of("response", List.of(ref("order"), value("201", Token.Form.UNQUOTED))),
                ReferenceGrammar.parse("response<order 201>"));
        assertEquals(TypeRef.of("box", List.of(new TypeArgument.Ref(TypeRef.of("pair",
                        List.of(ref("text"), value("a b", Token.Form.SINGLE_LINE_QUOTED)))))),
                ReferenceGrammar.parse("box<pair<text \"a b\">>"));
        assertEquals(TypeRef.of("p", List.of(value("-1", Token.Form.UNQUOTED), value("1box", Token.Form.UNQUOTED))),
                ReferenceGrammar.parse("p<-1 1box>"));
    }

    /** Identity is the parsed form: whitespace and a comma between arguments are free, and the text is one. */
    @Test
    void twoSpellingsAreOneReferenceWithOneCanonicalText() {
        TypeRef canonical = ReferenceGrammar.parse("map<text integer>");
        for (String spelling : List.of("map< text integer >", "map<text, integer>", "map<text\tinteger,>")) {
            assertEquals(canonical, ReferenceGrammar.parse(spelling), spelling);
            assertEquals("map<text integer>", ReferenceGrammar.parse(spelling).text(), spelling);
        }
        assertEquals("p<\"a \\\"b\\\"\">", ReferenceGrammar.parse("p< \"a \\\"b\\\"\" >").text());
    }

    /** An argument's channel is no part of the reference: an unquoted token filed as a value is the name it spells. */
    @Test
    void anArgumentComparesByItsText() {
        assertEquals(TypeRef.of("names", List.of(value("red", Token.Form.UNQUOTED))),
                ReferenceGrammar.parse("names<red>"));
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
                ReferenceGrammar.names(ReferenceGrammar.parse("response<box<order> 201>")));
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
