package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ReadException;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                !!meta:"https://tson.io/2026/39/m/meta.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                {
                  contact => %s
                  person => {
                    name: text
                    reach: contact
                    inline: (text | boolean)
                  }
                }""".formatted(contact);
        Tson tson = Tson.standard();
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
        ReadException e = assertThrows(ReadException.class,
                () -> read(personReader("(text | integer)"), "true"));

        assertTrue(e.getMessage().contains("[text, integer]"), e.getMessage());
    }

    /**
     * Both variants are the string class, so [TSON-DATA] §4's one pass cannot separate them however disjoint
     * their value sets are -- §5.4 makes the tag REQUIRED, and omitting it is a validation error.
     */
    @Test
    void aTagIsRequiredWhereTheVariantsShareABaseTypeClass() {
        ReadException e = assertThrows(ReadException.class,
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
        SchemaValidationException e = assertThrows(SchemaValidationException.class,
                () -> personReader("(text | text)"));

        assertTrue(e.getMessage().contains("§5.4"), e.getMessage());
    }

    // ---------------------------------------------------------------- §5.4's straddling float

    /**
     * {@code loose} lists core's {@code float64} as declared, which admits NaN and the infinities and so has no
     * class ([TSON-SCHEMA] §5.4); {@code tight} lists a float narrowing both facets, which keeps {@code number}.
     */
    private static final String STRADDLING = """
            !!id:"https://example.test/choice-straddling.tn"
            !!meta:"https://tson.io/2026/39/m/meta.tn"
            !!import:"https://tson.io/2026/39/m/core.tn"
            {
              loose => ( float64 | text )
              strict => !float_type { format: BINARY64  allow_nan: false  allow_infinity: false }
              tight => ( strict | text )
              holder => { loose: loose  tight: tight }
            }""";

    private static TsonValue readHolder(String loose, String tight) {
        Tson tson = Tson.standard();
        TsonTypeReader<?> reader = tson.treeRegistry().compile(tson.resolve(STRADDLING)).get("holder");
        return (TsonValue) reader.read(TestDocuments.document("{ loose: " + loose + "  tight: " + tight + " }"));
    }

    private static boolean disjoint(TsonLinkedSchema linked, String name) {
        return ((ChoiceBody) linked.schema().entries().get(name)).disjoint().orElseThrow();
    }

    @Test
    void aChoiceListingAFloatAdmittingSpecialsIsNotDisjoint() {
        TsonLinkedSchema linked = Tson.standard().resolve(STRADDLING);

        assertFalse(disjoint(linked, "loose"));
        assertTrue(disjoint(linked, "tight"));
    }

    @Test
    void anUntaggedFiniteFloatAtANonDisjointChoiceNeedsItsTag() {
        ReadException e = assertThrows(ReadException.class, () -> readHolder("1.5", "1.5"));

        assertEquals(Diagnostic.Code.TYPE_MISMATCH, e.diagnostic().code());
        assertTrue(e.getMessage().contains("requires an explicit type annotation"), e.getMessage());
    }

    @Test
    void anUntaggedNanAtANonDisjointChoiceNeedsItsTag() {
        ReadException e = assertThrows(ReadException.class, () -> readHolder(".nan", "1.5"));

        assertEquals(Diagnostic.Code.TYPE_MISMATCH, e.diagnostic().code());
        assertTrue(e.getMessage().contains("requires an explicit type annotation"), e.getMessage());
    }

    @Test
    void aTaggedNanAtANonDisjointChoiceReads() {
        TsonValue loose = readHolder("!float64 .nan", "1.5").get("loose");

        assertEquals(Optional.of("float64"), loose.typeRef());
        assertTrue(Double.isNaN(loose.asNumber().orElseThrow().doubleValue()), loose.toString());
    }

    @Test
    void aNarrowedFloatKeepsItsClassSoItsChoiceReadsUntagged() {
        TsonValue holder = readHolder("!float64 1.5", "1.5");

        assertEquals(Optional.of("strict"), holder.get("tight").typeRef());
        assertEquals(1.5, holder.get("tight").asDouble().orElseThrow());
    }

    @Test
    void aQuotedNanAtTheDisjointChoiceIsText() {
        TsonValue tight = readHolder("!float64 1.5", "\".nan\"").get("tight");

        assertEquals(Optional.of("text"), tight.typeRef());
        assertEquals(Optional.of(".nan"), tight.asString());
    }

    /** {@code .nan} is number-class, so it dispatches to {@code strict}, whose {@code allow_nan: false} refuses it. */
    @Test
    void anUntaggedNanAtTheDisjointChoiceIsRefusedByTheNarrowedFloat() {
        ReadException e = assertThrows(ReadException.class, () -> readHolder("!float64 1.5", ".nan"));

        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, e.diagnostic().code());
        assertTrue(e.getMessage().contains("allow_nan: false"), e.getMessage());
    }
}
