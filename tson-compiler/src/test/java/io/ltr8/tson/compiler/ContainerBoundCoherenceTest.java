package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonCanonicalIdentity;
import io.ltr8.tson.schema.TsonSchemaValidationException;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [TSON-SCHEMA] §5.3's {@code min_items <= max_items} rule, asked of every spelling that reaches it.
 *
 * <p><b>The point of this class is that they agree.</b> A size specifier and the {@code !array { ...
 * min_items: ... }} body it desugars to denote one type, and a template closing onto the same bounds
 * denotes it again -- so a rule stated with any one spelling refuses that one and admits the others. It is
 * stated instead on {@code ArrayBody}/{@code MapBody} ({@code Product.coherenceCheck}, the structural twin
 * of {@code Atom.coherenceCheck}), and asked at the two phases where a body becomes concrete: resolution
 * for a declared one, linking for the entries materialisation mints.
 *
 * <p>{@code ContainerSugarEndToEndTest} owns the sugar form's own behaviour and {@code SchemaDesugarerTest}
 * owns what that phase produces; what is here is the comparison between spellings.
 */
class ContainerBoundCoherenceTest {

    private static final String ID = "https://example.test/bounds.tn";

    private static TsonCompiledSchema compile(String declarations) {
        String schema = """
                !!id:"https://example.test/bounds.tn"
                !!meta:"https://tson.io/2026/34/m/meta.tn"
                !!import:"https://tson.io/2026/34/m/core.tn"
                {
                %s
                }
                """.formatted(declarations);
        TsonSchemaSource source = uri -> {
            if (TsonCanonicalIdentity.sameIdentity(uri, ID)) {
                return schema;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        TsonCompiledMetaRegistry core =
                TsonCompiledMetaRegistry.withStandardLibrary(SchemaMetaNameBinder.defaultContext(), source);
        return TsonCompiledSchemaRegistry.tree(core).get(ID);
    }

    private static TsonSchemaValidationException refused(String declarations) {
        return assertThrows(TsonSchemaValidationException.class, () -> compile(declarations), declarations);
    }

    /**
     * <b>Both spellings of one array, one answer.</b> The explicit constructor body is the form the sugar
     * desugars to, so an author who writes it by hand has written the same type -- and used to get the
     * opposite verdict, because the rule lived in the phase that only ever sees the sugar.
     */
    @Test
    void aSizeSpecifierAndTheBodyItDenotesAreRefusedAlike() {
        for (String declaration : List.of(
                "  bad => [text; 5..3]",
                "  bad => !array { element_type: text  min_items: 5  max_items: 3 }")) {
            assertTrue(refused(declaration).getMessage().contains("min_items 5 is above max_items 3"),
                    declaration);
        }
    }

    /** The map tier carries the identical pair, and shares the rule rather than restating it. */
    @Test
    void aMapIsJudgedByTheSameRuleAsAnArray() {
        for (String declaration : List.of(
                "  bad => {text => integer; 5..3}",
                "  bad => !map { key_type: text  value_type: integer  min_items: 5  max_items: 3 }")) {
            assertTrue(refused(declaration).getMessage().contains("min_items 5 is above max_items 3"),
                    declaration);
        }
    }

    /**
     * <b>And the third spelling: bounds that were parameters when the template was written.</b> §8.2 puts
     * this at materialisation -- "runs the value-level checks that open bounds deferred: family coherence
     * rules whose operands were parameters" -- because there is nothing to compare until an application
     * supplies both. The template itself is fine and stays fine; what is refused is the application.
     *
     * <p><b>No name leads the message</b>, and that is the rule rather than an omission: the only entries
     * reaching this check are ones resolution never produced, whose names are content-derived
     * ({@code integer_type_10_3_0794a6fb}) and appear nowhere in the author's file. The diagnostic's own
     * location names the declaration that wrote the application, which is the line they can edit.
     */
    @Test
    void boundsThatOnlyBecomeConcreteAtMaterialisationAreJudgedToo() {
        TsonSchemaValidationException thrown = refused("""
                  sized => <MIN, MAX> !array { element_type: text  min_items: MIN  max_items: MAX }
                  bad   => { b: sized<10, 3> }""");

        assertTrue(thrown.getMessage().contains("min_items 10 is above max_items 3"), thrown.getMessage());
        // A derived name is <form>_<operands>_<hash8>; nothing shaped like one may reach an author.
        assertFalse(thrown.getMessage().matches("(?s).*\\b\\w+_[0-9a-f]{8}\\b.*"),
                "no content-derived entry name: " + thrown.getMessage());
    }

    /**
     * <b>§8.2's "and their kin" needs no enumeration.</b> An atom's bounds can be parametric too -- through
     * the {@code *_type} constructor spelling, since §12.1 refuses a parameterized {@code ^} refinement --
     * and the rule that closes over them is the family's own, the same one that judges the literal body. So
     * every family's coherence rule reaches materialisation together, and a family gaining a rule later
     * arrives here for free.
     */
    @Test
    void everyFamilysOwnCoherenceRuleReachesMaterialisationToo() {
        assertTrue(refused("""
                  b => <N> !integer_type { min: N  max: 3 }
                  r => { x: b<10> }""").getMessage().contains("min 10 is above max 3"));

        assertTrue(refused("""
                  b => <N> !text_type { min_length: N  max_length: 3 }
                  r => { x: b<10> }""").getMessage().contains("min_length 10 is above max_length 3"));

        assertTrue(refused("""
                  b => <N> !cidr4_type { min_prefix: N  max_prefix: 8 }
                  r => { x: b<40> }""").getMessage().contains("min_prefix 40 is above max_prefix 8"));
    }

    /** And a coherent application of each still closes -- the rule fires on the bounds, not on being parametric. */
    @Test
    void aCoherentParametricAtomApplicationIsUntouched() {
        assertNotNull(compile("""
                  b => <N> !integer_type { min: N  max: 30 }
                  r => { x: b<10> }"""));
    }

    /** A template with an incoherent application is not itself broken: another application of it still closes. */
    @Test
    void aCoherentApplicationOfTheSameTemplateIsUntouched() {
        assertNotNull(compile("""
                  sized => <MIN, MAX> !array { element_type: text  min_items: MIN  max_items: MAX }
                  ok    => { a: sized<1, 5> }"""));
    }

    /**
     * <b>A range admitting exactly one value is a legitimate way to pin a length</b>, not an incoherence --
     * the same judgement {@code AtomCoherence} makes for every other inclusive bound pair, which is why
     * these families share its comparison instead of each writing {@code >}.
     */
    @Test
    void equalBoundsPinALengthAndAreCoherent() {
        assertNotNull(compile("""
                  triple => [text; 3]
                  exact  => !map { key_type: text  value_type: integer  min_items: 2  max_items: 2 }"""));
    }

    /** One bound alone is unbounded on the other side and contradicts nothing. */
    @Test
    void aSingleBoundIsAlwaysCoherent() {
        assertNotNull(compile("""
                  atLeast => !array { element_type: text  min_items: 5 }
                  atMost  => !map { key_type: text  value_type: integer  max_items: 2 }"""));
    }
}
