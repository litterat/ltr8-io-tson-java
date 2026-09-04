package io.ltr8.tson;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [TSON-SCHEMA] §4.2's <b>level discipline</b>: an entry that composes with, refines, or subtracts from a
 * constructor MUST itself be declared {@code ~}.
 *
 * <p><b>What it protects is the two IS-A indexes.</b> Unchecked, {@code composed => c & { ... }} over a
 * constructor {@code c} resolved to {@code constructor: false} carrying {@code supertypes=[c, product, top]},
 * and {@code c} gained {@code subtypes=[composed]} -- so the two relations §4.2 keeps apart ("types relate to
 * types, and constructors relate to constructors and kinds") were mixed in exactly the two indexes §7.2's
 * subsumption rule reads.
 *
 * <p>The rule is <b>one-directional</b>, which is why the check asks only whether the operand is a
 * constructor: a non-constructor operand in a {@code ~} declaration stays legal, and that is what lets a base
 * kind seed the level and a record mixin lend vocabulary.
 */
class ConstructorLevelDisciplineTest {

    private static final String ID = "https://example.test/m.tn";

    private static String schema(String declarations) {
        return """
                !!id:"https://example.test/m.tn"
                !!meta:"https://tson.io/2026/35/m/meta-kernel.tn"
                !!import:"https://tson.io/2026/35/m/meta-kernel.tn"
                {
                %s }
                """.formatted(declarations);
    }

    private static List<Diagnostic> problems(String declarations) {
        String source = schema(declarations);
        return Tson.builder().schemaSource(TsonSchemaSource.ofMap(Map.of(ID, source))).build()
                .validateSchema(source);
    }

    private static void assertRefused(List<Diagnostic> problems, String operand) {
        assertEquals(1, problems.size(), problems::toString);
        Diagnostic only = problems.getFirst();
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, only.code());
        assertTrue(only.message().contains("'" + operand + "' is a type constructor"), only::toString);
        assertTrue(only.message().contains("§4.2's level discipline"), only::toString);
    }

    @Test
    void composingWithAConstructorRequiresTheMarker() {
        assertRefused(problems("""
                  c        => ~product & { id: identifier }
                  composed => c & { extra: identifier }
                """), "c");
    }

    /** A kernel constructor is the same case and the one an author is likelier to reach for. */
    @Test
    void refiningAConstructorRequiresTheMarker() {
        assertRefused(problems("  refined => array ^ { unordered: = true }"), "array");
    }

    /**
     * §5.9's removal clause applies to a composition, so its operand arrives as a supertype -- which is why
     * subtraction needs no check site of its own, and why that is worth pinning rather than reasoning about.
     */
    @Test
    void subtractingFromAConstructorRequiresTheMarker() {
        assertRefused(problems("""
                  c           => ~product & { id: identifier  spare: identifier }
                  subtracted  => c & {} - { spare }
                """), "c");
    }

    /** With the marker, the same derivation is exactly what a meta-schema is for. */
    @Test
    void withTheMarkerTheSameDerivationIsLegal() {
        assertEquals(List.of(), problems("""
                  c       => ~product & { id: identifier }
                  derived => ~c & { extra: identifier }
                """));
    }

    /**
     * The other direction of the one-directional rule: a {@code ~} declaration may take non-constructor
     * operands, which is how a base kind seeds the level and a record mixin lends vocabulary (§4.2 names
     * {@code atom_specification} for the second).
     */
    @Test
    void aMarkedDeclarationMayTakeNonConstructorOperands() {
        assertEquals(List.of(), problems("  mine => ~product & atom_specification & { id: identifier }"));
    }

    /** An ordinary type over an ordinary type is untouched -- the overwhelmingly common case. */
    @Test
    void anOrdinaryDerivationIsUnaffected() {
        assertEquals(List.of(), problems("""
                  base    => { id: identifier }
                  derived => base & { extra: identifier }
                """));
    }

    /**
     * Construction is not derivation, and §5.5 says so: {@code !C { ... }} transfers {@code C}'s kind and no
     * supertypes, so it mixes no index and needs no marker. It is also the remedy the refusal names.
     */
    @Test
    void applyingAConstructorNeedsNoMarker() {
        assertEquals(List.of(), problems("  boxed => !array { element_type: identifier }"));
    }

    /**
     * The standard library is the evidence that enforcing this breaks nothing: no entry in any bundled
     * schema is a non-constructor whose supertypes reach a constructor. Asserted rather than recorded,
     * because a future edit that introduced one would otherwise fail somewhere far less legible.
     */
    @Test
    void noBundledSchemaMixesTheTwoLevels() {
        Tson tson = Tson.builder().build();
        for (String id : List.of(TsonBundledSchemas.META_KERNEL_ID, TsonBundledSchemas.META_ID,
                TsonBundledSchemas.CORE_ID)) {
            Map<String, TypeDefinition> entries =
                    tson.schemaRegistry().get(id).orElseThrow().schema().entries();
            entries.forEach((name, definition) -> {
                if (definition.constructor()) {
                    return;
                }
                for (String supertype : definition.supertypes()) {
                    TypeDefinition parent = entries.get(supertype);
                    assertTrue(parent == null || !parent.constructor(),
                            () -> id + ": '" + name + "' is not a constructor but derives from '" + supertype
                                    + "', which is");
                }
            });
        }
    }
}
