package io.ltr8.tson.compiler;

import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.SchemaValidationException;

import org.junit.jupiter.api.Test;

import java.util.List;

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
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !!import:"https://tson.io/2026/40/m/core.tn"
                !!import:"https://tson.io/2026/40/m/net.tn"
                !schema {
                %s
                }
                """.formatted(declarations);
        SchemaSource source = uri -> {
            if (CanonicalIdentity.sameIdentity(uri, ID)) {
                return schema;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        TsonCompiledVocabularyRegistry core =
                TsonCompiledVocabularyRegistry.withStandardLibrary(SchemaModelNameBinder.defaultContext(), source);
        return TsonCompiledSchemaRegistry.tree(core).get(ID);
    }

    private static SchemaValidationException refused(String declarations) {
        return assertThrows(SchemaValidationException.class, () -> compile(declarations), declarations);
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
                "  bad => !meta.array { element_type: text  min_items: 5  max_items: 3 }")) {
            assertTrue(refused(declaration).getMessage().contains("min_items 5 is above max_items 3"),
                    declaration);
        }
    }

    /** The map tier carries the identical pair, and shares the rule rather than restating it. */
    @Test
    void aMapIsJudgedByTheSameRuleAsAnArray() {
        for (String declaration : List.of(
                "  bad => {text => integer; 5..3}",
                "  bad => !meta.map { key_type: text  value_type: integer  min_items: 5  max_items: 3 }")) {
            assertTrue(refused(declaration).getMessage().contains("min_items 5 is above max_items 3"),
                    declaration);
        }
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
                  pinned_pair  => !meta.map { key_type: text  value_type: integer  min_items: 2  max_items: 2 }"""));
    }

    /** One bound alone is unbounded on the other side and contradicts nothing. */
    @Test
    void aSingleBoundIsAlwaysCoherent() {
        assertNotNull(compile("""
                  atLeast => !meta.array { element_type: text  min_items: 5 }
                  atMost  => !meta.map { key_type: text  value_type: integer  max_items: 2 }"""));
    }
}
