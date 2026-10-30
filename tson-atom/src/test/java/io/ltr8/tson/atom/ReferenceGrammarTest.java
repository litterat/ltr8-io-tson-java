package io.ltr8.tson.atom;

import io.ltr8.tson.schema.meta.Token;
import io.ltr8.tson.schema.meta.TypeArgument;
import io.ltr8.tson.schema.meta.TypeRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reference grammar a reference-typed slot's value is read by (SPEC-FEEDBACK.md #12): segments, an optional
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
        assertFalse(ReferenceGrammar.isApplication("order"));
    }

    /** A number or a quoted token is a value; every other token is a reference, nested ones included. */
    @Test
    void anArgumentIsAReferenceOrAToken() {
        assertEquals(new TypeRef("response", List.of(ref("order"), value("201", Token.Form.UNQUOTED))),
                ReferenceGrammar.parse("response<order 201>"));
        assertEquals(new TypeRef("box", List.of(new TypeArgument.Ref(new TypeRef("pair",
                        List.of(ref("text"), value("a b", Token.Form.SINGLE_LINE_QUOTED)))))),
                ReferenceGrammar.parse("box<pair<text \"a b\">>"));
        assertTrue(ReferenceGrammar.isApplication("maxint<10>"));
    }

    /** Identity is the parsed form: whitespace and a comma between arguments are free, and the text is one. */
    @Test
    void twoSpellingsAreOneReferenceWithOneCanonicalText() {
        TypeRef canonical = ReferenceGrammar.parse("map<text integer>");
        for (String spelling : List.of("map< text integer >", "map<text, integer>", "map <text\tinteger>")) {
            assertEquals(canonical, ReferenceGrammar.parse(spelling), spelling);
            assertEquals("map<text integer>", ReferenceGrammar.parse(spelling).text(), spelling);
        }
        assertEquals("p<\"a \\\"b\\\"\">", ReferenceGrammar.parse("p< \"a \\\"b\\\"\" >").text());
    }

    /** An argument's channel is no part of the reference: an unquoted token filed as a value is the name it spells. */
    @Test
    void anArgumentComparesByItsText() {
        assertEquals(new TypeRef("names", List.of(value("red", Token.Form.UNQUOTED))),
                ReferenceGrammar.parse("names<red>"));
    }

    @Test
    void aMalformedReferenceIsRefusedWithTheGrammarsReason() {
        for (String text : List.of("box<text", "box<>", "box<text>>", "box<<text>", ".box", "box..x", "box<text>x",
                "", "1box", "box<\"open>")) {
            assertThrows(AtomParseException.class, () -> ReferenceGrammar.parse(text), text);
            assertFalse(ReferenceGrammar.isApplication(text), text);
        }
    }

    @Test
    void theNamesAReferenceWritesAreItsHeadAndItsReferenceArguments() {
        assertEquals(List.of("response", "box", "order"),
                ReferenceGrammar.names(ReferenceGrammar.parse("response<box<order> 201>")));
    }
}
