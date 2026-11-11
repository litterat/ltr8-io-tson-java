package io.ltr8.tson.compiler;

import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.SchemaValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [TSON-DATA] §4.3's numeric equivalence, applied where [TSON-SCHEMA] §8.2 compares a type written in place by its
 * structure. {@code 255} and {@code 0xFF} are one number, read as one count, so a size bound that differs only in
 * which one the author wrote is one construction.
 *
 * <p><b>The stake is a verdict.</b> §5.4 requires a choice's variants to be distinct types, so {@code ( [float32; 255]
 * | [float32; 0xFF] )} is a choice between two identical variants that no untagged read could ever discriminate.
 */
class NumericBoundIdentityTest {

    private static final String ID = "https://example.test/numeric-identity.tn";

    private static TsonCompiledSchema compile(String declarations) {
        String schema = """
                !!id:"https://example.test/numeric-identity.tn"
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !!import:"https://tson.io/2026/40/m/core.tn"
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
        return TsonCompiledSchemaRegistry.tree(TsonCompiledVocabularyRegistry
                .withStandardLibrary(SchemaModelNameBinder.defaultContext(), source)).get(ID);
    }

    /**
     * This schema's own entries named with a hash suffix, as an entry keyed by its content would be -- its closure's
     * left out.
     */
    private static List<String> derivedEntries(TsonCompiledSchema compiled) {
        String own = io.ltr8.tson.base.CanonicalIdentity.canonicalize(compiled.schema().id());
        return compiled.schema().entries().keySet().stream().filter(n -> n.matches(".*_[0-9a-f]{8}"))
                .filter(n -> compiled.linkedSchema().originOf(n).equals(own)).toList();
    }

    /**
     * <b>The verdict this exists for.</b> Two spellings of one number are one variant, so §5.4 refuses the
     * choice -- as it does for two spellings of one name, on the same footing.
     */
    @Test
    void aChoiceOfOneTypeSpelledTwoWaysIsRefusedLikeAnyOtherRepeat() {
        SchemaValidationException thrown = assertThrows(SchemaValidationException.class,
                () -> compile("  u => ( [float32; 255] | [float32; 0xFF] )"));

        assertTrue(thrown.getMessage().contains("twice"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("§5.4"), thrown.getMessage());
    }

    /**
     * §4.3's equivalence in full: radix, digit separators and a redundant sign all fall away, so the two fields hold
     * one construction in place, and no entry is added for either.
     */
    @ParameterizedTest
    @ValueSource(strings = {"0xFF", "0b1111_1111", "0o377", "+255", "255"})
    void everyIntegerSpellingOfOneMagnitudeIsOneConstruction(String spelling) {
        TsonCompiledSchema compiled = compile("  u => { a: [float32; 255]  b: [float32; %s] }".formatted(spelling));
        io.ltr8.tson.schema.meta.RecordBody u =
                (io.ltr8.tson.schema.meta.RecordBody) compiled.schema().entries().get("u");

        assertEquals(u.fields().get("a").type(), u.fields().get("b").type(), spelling);
        assertEquals(List.of(), derivedEntries(compiled), spelling);
    }
}
