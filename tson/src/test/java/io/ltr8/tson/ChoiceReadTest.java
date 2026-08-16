package io.ltr8.tson;

import io.ltr8.tson.compiler.TsonReadException;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.schema.TsonSchemaValidationException;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §5.4's choice type, written as an author writes it -- the {@code (A | B)} sugar -- from schema source
 * through to a read value.
 *
 * <p>§5.4's Tagging rule is what these pin: a value at a choice position carries a {@code !variant}
 * annotation selecting its variant, <em>unless</em> the variants are disjoint under the encoding's own
 * discrimination, which for TSON text is the single base-type-resolution pass of [TSON-DATA] §4. So {@code
 * (text | integer)} reads untagged -- the string and number classes separate them -- while {@code (text |
 * uri)} always requires the tag, both variants being the string class however disjoint their value sets are.
 *
 * <p>Where {@code ChoiceReaderTest} exercises the reader over a hand-built {@code ChoiceBody}, these run the
 * whole pipeline: the sugar desugars to {@code !choice { variants: [...] } }, resolves to a real entry, links
 * (which is where {@code ChoiceDisjointness} derives the fact the tagging rule turns on) and compiles.
 */
class ChoiceReadTest {

    /** A {@code person} whose {@code reach} field is the named choice given, plus an inline one alongside it. */
    private static TsonTypeReader<?> personReader(String contact) {
        String schema = """
                !!id:"https://example.test/choice-read.tn"
                !!meta:"https://tson.io/2026/32/m/meta.tn"
                !!import:"https://tson.io/2026/32/m/core.tn"
                {
                  contact => %s
                  person => {
                    name: text
                    reach: contact
                    inline: (text | boolean)
                  }
                }""".formatted(contact);
        Tson tson = Tson.builder().build();
        return tson.treeRegistry().compile(tson.resolve(schema)).get("person");
    }

    private static TsonValue read(TsonTypeReader<?> reader, String reach) {
        return (TsonValue) reader.read(TestDocuments.document(
                "{ name: \"Ada\"  reach: " + reach + "  inline: true }"));
    }

    /** Disjoint by base-type class, so the tag is omissible and the variant comes back off the same §4 pass. */
    @Test
    void anUntaggedValueIsRecoveredWhereTheVariantsAreDisjoint() {
        TsonValue person = read(personReader("(text | integer)"), "\"ada@example.com\"");

        assertEquals(Optional.of("text"), person.get("reach").typeRef());
        assertEquals(Optional.of("ada@example.com"), person.get("reach").asString());
    }

    /** The other variant of the same choice, separated by nothing but its base-type class. */
    @Test
    void eachDisjointVariantIsRecoveredAsItself() {
        TsonValue person = read(personReader("(text | integer)"), "42");

        assertEquals(Optional.of("integer"), person.get("reach").typeRef());
    }

    /** An explicit tag dispatches by variant name, whether or not the choice could have gone untagged. */
    @Test
    void anExplicitTagSelectsTheVariant() {
        TsonValue person = read(personReader("(text | integer)"), "!integer 42");

        assertEquals(Optional.of("integer"), person.get("reach").typeRef());
    }

    /** Untagged recovery fails closed: no variant matches the boolean class, and the diagnostic names the set. */
    @Test
    void aValueMatchingNoVariantIsRejected() {
        TsonReadException e = assertThrows(TsonReadException.class,
                () -> read(personReader("(text | integer)"), "true"));

        assertTrue(e.getMessage().contains("[text, integer]"), e.getMessage());
    }

    /**
     * Both variants are the string class, so [TSON-DATA] §4's one pass cannot separate them however disjoint
     * their value sets are -- §5.4 makes the tag REQUIRED, and omitting it is a validation error.
     */
    @Test
    void aTagIsRequiredWhereTheVariantsShareABaseTypeClass() {
        TsonReadException e = assertThrows(TsonReadException.class,
                () -> read(personReader("(text | uri)"), "\"https://example.test\""));

        assertTrue(e.getMessage().contains("requires an explicit type annotation"), e.getMessage());
    }

    /** The same choice, tagged, reads fine -- the tag is what was missing, not the value. */
    @Test
    void aSameBaseClassChoiceReadsWhenTagged() {
        TsonValue person = read(personReader("(text | uri)"), "!uri \"https://example.test\"");

        assertEquals(Optional.of("uri"), person.get("reach").typeRef());
    }

    /**
     * The inline field carries its own {@code (text | boolean)} -- hoisted into an injected declaration by
     * {@code SchemaDesugarer} -- and reads exactly as the named one does.
     */
    @Test
    void anInlineChoiceReadsLikeADeclaredOne() {
        TsonValue person = read(personReader("(text | integer)"), "42");

        assertEquals(Optional.of("boolean"), person.get("inline").typeRef());
    }

    /**
     * §5.4's distinct-variant rule, reached from source: the sugar resolves and the linker rejects it, so a
     * duplicate never reaches a reader that could not discriminate it anyway.
     */
    @Test
    void aChoiceWithADuplicateVariantIsRejected() {
        TsonSchemaValidationException e = assertThrows(TsonSchemaValidationException.class,
                () -> personReader("(text | text)"));

        assertTrue(e.getMessage().contains("§5.4"), e.getMessage());
    }
}
