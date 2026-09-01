package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonCanonicalIdentity;
import io.ltr8.tson.schema.TsonSchemaValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.stream.Collectors;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [TSON-DATA] §4.3's numeric equivalence, applied where [TSON-SCHEMA] §8.2 derives an entry's identity
 * ({@link io.ltr8.tson.compiler.resolver} {@code NumericIdentity}). {@code 255} and {@code 0xFF} are one
 * number, so an application that differs only in which one the author wrote is one application and mints
 * one entry.
 *
 * <p><b>The stake is a verdict, not a redundant entry.</b> §5.4 requires a choice's variants to resolve to
 * distinct types, and it asks that question of entry names -- so two names for one type passed a check two
 * spellings of one name failed, and {@code ( [float32; 255] | [float32; 0xFF] )} was admitted as a choice
 * between two identical variants that no untagged read could ever discriminate.
 */
class NumericArgumentIdentityTest {

    private static final String ID = "https://example.test/numeric-identity.tn";

    private static TsonCompiledSchema compile(String declarations) {
        String schema = """
                !!id:"https://example.test/numeric-identity.tn"
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
        return TsonCompiledSchemaRegistry.tree(TsonCompiledMetaRegistry
                .withStandardLibrary(SchemaMetaNameBinder.defaultContext(), source)).get(ID);
    }

    /** Every derived entry, which is where two spellings of one number would show up as two. */
    private static List<String> derivedEntries(TsonCompiledSchema compiled) {
        return compiled.schema().entries().keySet().stream().filter(n -> n.matches(".*_[0-9a-f]{8}")).toList();
    }

    /**
     * The headline case, through a template so both naming sites are exercised at once: the lifted
     * {@code array} synthetic (named by {@code SchemaDesugarer} from the binding record) and the
     * {@code vector} instantiation (named by {@code TemplateMaterialiser} from the application).
     */
    @Test
    void oneNumberSpelledTwoWaysIsOneEntryAtBothNamingSites() {
        List<String> derived = derivedEntries(compile("""
                  vector => <T, N> { v: [T; N] }
                  use    => { a: vector<float32, 255>  b: vector<float32, 0xFF> }"""));

        assertEquals(1, derived.stream().filter(n -> n.startsWith("array_float32_")).count(), derived::toString);
        assertEquals(1, derived.stream().filter(n -> n.startsWith("vector_")).count(), derived::toString);
        assertTrue(derived.stream().anyMatch(n -> n.startsWith("array_float32_255_255_")),
                () -> "named by the canonical spelling: " + derived);
    }

    /**
     * <b>The verdict this exists for.</b> Two spellings of one number are one variant, so §5.4 refuses the
     * choice -- as it always did for two spellings of one name, and now does for these on the same footing.
     */
    @Test
    void aChoiceOfOneTypeSpelledTwoWaysIsRefusedLikeAnyOtherRepeat() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> compile("  u => ( [float32; 255] | [float32; 0xFF] )"));

        assertTrue(thrown.getMessage().contains("twice"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("§5.4"), thrown.getMessage());
    }

    /** §4.3's equivalence in full: radix, digit separators and a redundant sign all fall away. */
    @ParameterizedTest
    @ValueSource(strings = {"0xFF", "0b1111_1111", "0o377", "+255", "255"})
    void everyIntegerSpellingOfOneMagnitudeLandsOnOneEntry(String spelling) {
        List<String> derived = derivedEntries(compile(
                "  u => { a: [float32; 255]  b: [float32; %s] }".formatted(spelling)));

        // The count is the assertion, not the hash: §8.2 makes a derived name non-normative, so pinning the
        // digits would pin an implementation detail and break on any change to the canonical rendering.
        assertEquals(1, derived.size(), () -> spelling + " -> " + derived);
        assertTrue(derived.getFirst().startsWith("array_float32_255_255_"), () -> spelling + " -> " + derived);
    }

    /** A float's written scale falls away too, an exponent being one more spelling of the same magnitude. */
    @ParameterizedTest
    @ValueSource(strings = {"1.00", "1e0", "1.0"})
    void everyFloatSpellingOfOneMagnitudeLandsOnOneEntry(String spelling) {
        List<String> derived = derivedEntries(compile("""
                  box => <T, N> { v: T = N }
                  u   => { a: box<float64, 1.0>  b: box<float64, %s> }""".formatted(spelling)));

        assertEquals(1, derived.size(), () -> spelling + " -> " + derived);
    }

    /**
     * <b>The equivalence stops at the base type, and deliberately.</b> §4 resolves {@code 1} to an integer
     * and {@code 1.0} to a float, so they are two values however equal their magnitudes -- merging them
     * would be an equivalence this implementation invented rather than one §4.3 states.
     *
     * <p><b>The float's point cannot appear in a name</b>, [TSON-DATA] §7.7 not admitting {@code .} and
     * [TSON-SCHEMA] §8.2 requiring a minted name to be an identifier, so {@code 1.0} reads as its digits
     * plus a hash of the text they came from ({@code InternalName}). Both halves earn their place here: the
     * digits say which entry this is, and the hash is what keeps {@code 1.0} apart from a hypothetical
     * {@code 1_0} in the readable name rather than only in the structural hash at the end.
     */
    @Test
    void anIntegerAndAFloatOfOneMagnitudeStayApart() {
        List<String> derived = derivedEntries(compile("""
                  box => <T, N> { v: T = N }
                  u   => { a: box<float64, 1>  b: box<float64, 1.0> }"""));

        assertEquals(2, derived.size(), derived::toString);
        Set<String> readable = derived.stream().map(n -> n.substring(0, n.lastIndexOf('_')))
                .collect(Collectors.toSet());
        assertTrue(readable.contains("box_float64_1"), () -> "the integer reads plainly: " + derived);
        assertTrue(readable.stream().anyMatch(n -> n.matches("box_float64_1_0_h[0-9a-f]{8}")),
                () -> "and the float as its digits plus a hash of '1.0': " + derived);
    }

    /** §4.4: a quoted token is a string whatever it spells, so nothing about it is numeric. */
    @Test
    void aQuotedTokenIsNeverANumber() {
        List<String> derived = derivedEntries(compile("""
                  box => <T, N> { v: T = N }
                  u   => { a: box<text, "255">  b: box<text, "0xFF"> }"""));

        assertEquals(2, derived.size(), derived::toString);
    }
}
