package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonCanonicalIdentity;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.ElementState;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.tree.TsonAbsent;
import io.ltr8.tson.tree.TsonArray;
import io.ltr8.tson.tree.TsonValue;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A nested bracket form ({@code [[T; 2], U]}, {@code [[T]; 3]}) in an ordinary user schema -- one governed by
 * {@code meta.tn} and importing {@code core.tn} -- driven through the real bundled chain, from the sugar an
 * author writes to a reader enforcing the inner container's own constraints. The end-to-end peer of {@code
 * SchemaDesugarerTest}'s nested-form cases, which pin the rewrite itself.
 *
 * <p>[TSON-SCHEMA] §5.3 says declaration-level container syntax nests inside itself and fixes the order --
 * {@code grid => <T, N> [[T; N]; N]} is {@code array_ranged<array_ranged<T, N, N>, N, N>}, "the inner form
 * desugaring first" -- and §12.1 says the same from the grammar side. {@code SchemaDesugarer} performs that
 * as a bottom-up hoist: the inner container becomes its own injected declaration and the position that held
 * it becomes a bare reference, so the outer container routes a plain name like any other. What these cover is
 * that the arrangement survives resolution, linking, compilation and a read.
 *
 * <p>No §5.10 substitution is involved. A nested form carries no parameter its flat sibling does not, which is
 * why this closes independently of the template work.
 */
class NestedContainerTest {

    private static final String ID = "https://example.test/nested-container.tn";

    /**
     * Resolves, links and compiles a user schema whose body is {@code declarations}; throws whatever the
     * pipeline throws.
     */
    private static TsonCompiledSchema compile(String declarations) {
        String schema = """
                !!id:"https://example.test/nested-container.tn"
                !!meta:"https://tson.io/2026/32/m/meta.tn"
                !!import:"https://tson.io/2026/32/m/core.tn"
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

    /** The entry a name resolves to in the compiled schema -- an injected one included. */
    private static TypeDefinition entry(TsonCompiledSchema compiled, String name) {
        TypeDefinition definition = compiled.schema().entries().get(name);
        assertNotNull(definition, () -> "no entry '" + name + "' among " + compiled.schema().entries().keySet());
        return definition;
    }

    /**
     * The whole arc for a tuple position holding a nested sized array: the position names an injected entry,
     * that entry is the {@code array_ranged} instantiation the flat spelling would have produced, and the
     * compiled reader takes real data.
     */
    @Test
    void aTuplePositionHoldingASizedArrayResolvesCompilesAndReads() {
        TsonCompiledSchema compiled = compile("  grid => [[integer; 2], text]");

        TupleBody outer = assertInstanceOf(TupleBody.class, entry(compiled, "grid").body());
        assertEquals(ElementState.REQUIRED, outer.elements().get(0).state());
        assertEquals(TypeRef.of("text"), outer.elements().get(1).elementType());

        ArrayBody inner = assertInstanceOf(ArrayBody.class,
                entry(compiled, outer.elements().get(0).elementType().name()).body());
        assertEquals(TypeRef.of("integer"), inner.elementType());
        assertEquals(Optional.of(BigInteger.TWO), inner.minItems());
        assertEquals(Optional.of(BigInteger.TWO), inner.maxItems());

        assertNotNull((TsonValue) compiled.get("grid").read(TestDocuments.document("[ [1 2] \"a\" ]")));
    }

    /** The inner container's constraints are live, not decorative: its bounds are enforced one bracket in. */
    @Test
    void theInnerContainersBoundsAreEnforcedWhenReading() {
        TsonCompiledSchema compiled = compile("  grid => [[integer; 2], text]");

        assertTrue(assertThrows(TsonReadException.class,
                () -> compiled.get("grid").read(TestDocuments.document("[ [1 2 3] \"a\" ]")))
                .getMessage().contains("maximum 2"));
        assertTrue(assertThrows(TsonReadException.class,
                () -> compiled.get("grid").read(TestDocuments.document("[ [1] \"a\" ]")))
                .getMessage().contains("minimum 2"));
    }

    /** The other nesting direction -- a sized array over a nested plain array -- and its own bound enforced. */
    @Test
    void aSizedArrayOverANestedArrayResolvesCompilesAndReads() {
        TsonCompiledSchema compiled = compile("  rows => [[integer]; 3]");

        ArrayBody outer = assertInstanceOf(ArrayBody.class, entry(compiled, "rows").body());
        assertEquals(Optional.of(new BigInteger("3")), outer.minItems());
        ArrayBody inner = assertInstanceOf(ArrayBody.class, entry(compiled, outer.elementType().name()).body());
        assertEquals(TypeRef.of("integer"), inner.elementType());
        assertEquals(Optional.empty(), inner.maxItems(), "the inner array is unconstrained");

        assertNotNull((TsonValue) compiled.get("rows")
                .read(TestDocuments.document("[ [1 2] [3] [] ]")));
        assertTrue(assertThrows(TsonReadException.class,
                () -> compiled.get("rows").read(TestDocuments.document("[ [1] [2] ]")))
                .getMessage().contains("minimum 3"));
    }

    /**
     * Structural sharing across nesting depth: §8.2 makes identity application-structural, so the one
     * injected {@code array<integer>} entry serves the nested position, the flat declaration and the inline
     * field alike -- one entry, three spellings.
     */
    @Test
    void identicalInnerContainersCollapseOntoOneInjectedEntry() {
        TsonCompiledSchema compiled = compile("""
                  rows     => [[integer]; 3]
                  flat     => [integer]
                  holder   => { r: rows  cells: [integer] }""");

        ArrayBody rows = assertInstanceOf(ArrayBody.class, entry(compiled, "rows").body());
        List<String> arrayEntries = compiled.schema().entries().keySet().stream()
                .filter(name -> name.startsWith("array_integer_")).toList();
        assertEquals(List.of(rows.elementType().name()), arrayEntries,
                "one injected array<integer>, shared by every spelling of it");
    }

    /**
     * §5.3's element {@code ?} end to end, through the form the spec states the rule with: "absent elements
     * occupy positional slots -- {@code [a _ c]} has three elements and satisfies a {@code [T?; 3]} size
     * constraint". The state and both bounds land on one binding record, and the compiled reader enforces
     * exactly that reading: {@code _} is admitted at an element position and still counts toward the size.
     */
    @Test
    void anOptionalElementAdmitsTheAbsentSentinelAndItCountsTowardTheSize() {
        TsonCompiledSchema compiled = compile("""
                  triple => [integer?; 3]
                  strict => [integer; 3]""");

        ArrayBody body = assertInstanceOf(ArrayBody.class, entry(compiled, "triple").body());
        assertEquals(ElementState.OPTIONAL, body.state());
        assertEquals(Optional.of(new BigInteger("3")), body.minItems());

        assertNotNull((TsonValue) compiled.get("triple").read(TestDocuments.document("[1 _ 3]")));
        assertNotNull((TsonValue) compiled.get("triple").read(TestDocuments.document("[_ _ _]")));
        assertTrue(assertThrows(TsonReadException.class,
                () -> compiled.get("triple").read(TestDocuments.document("[1 _]")))
                .getMessage().contains("minimum 3"), "an absent element occupies a slot, it does not vacate one");

        assertEquals(ElementState.REQUIRED,
                assertInstanceOf(ArrayBody.class, entry(compiled, "strict").body()).state(),
                "the unmarked form keeps REQUIRED from the vocabulary's own default");
        assertTrue(assertThrows(TsonReadException.class,
                () -> compiled.get("strict").read(TestDocuments.document("[1 _ 3]")))
                .getMessage().contains("elements are required"));
    }

    /** An absent element reaches the tree as {@code TsonAbsent}, in its own positional slot. */
    @Test
    void anAbsentElementReadsAsTsonAbsentInItsOwnSlot() {
        TsonCompiledSchema compiled = compile("  slots => [integer?]");

        TsonArray array = (TsonArray) compiled.get("slots").read(TestDocuments.document("[1 _ 3]"));
        assertEquals(3, array.elements().size());
        assertInstanceOf(TsonAbsent.class, array.get(1));
    }

    /** Nesting recurses: a third bracket is no special case, because the hoist is bottom-up. */
    @Test
    void aThreeDeepNestingResolvesCompilesAndReads() {
        TsonCompiledSchema compiled = compile("  deep => [[[integer]]]");

        ArrayBody outer = assertInstanceOf(ArrayBody.class, entry(compiled, "deep").body());
        ArrayBody middle = assertInstanceOf(ArrayBody.class, entry(compiled, outer.elementType().name()).body());
        ArrayBody innermost = assertInstanceOf(ArrayBody.class, entry(compiled, middle.elementType().name()).body());
        assertEquals(TypeRef.of("integer"), innermost.elementType());

        assertNotNull((TsonValue) compiled.get("deep").read(TestDocuments.document("[ [ [1 2] [3] ] [] ]")));
    }
}
