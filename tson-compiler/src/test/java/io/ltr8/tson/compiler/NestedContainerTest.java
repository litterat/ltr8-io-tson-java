package io.ltr8.tson.compiler;

import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.base.ReadException;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.tree.TsonVoid;
import io.ltr8.tson.tree.TsonArray;
import io.ltr8.tson.tree.TsonValue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A nested bracket form ({@code [[T; 2], U]}, {@code [[T]; 3]}) in an ordinary user schema -- one importing
 * {@code meta.tn} and {@code core.tn} -- driven through the real bundled chain, from the sugar an
 * author writes to a reader enforcing the inner container's own constraints. The end-to-end peer of {@code
 * SchemaDesugarerTest}'s nested-form cases, which pin the rewrite itself.
 *
 * <p>[TSON-SCHEMA] §5.3 says declaration-level container syntax nests inside itself and fixes the order --
 * {@code grid => <T, N> [[T; N]; N]} nests with "the inner form desugaring first" -- and §12.1 says the
 * same from the grammar side. (Revision 32 spells that nesting as an {@code array_ranged} application; the
 * size templates are gone from the kernel and a sized form binds its bounds directly, so what nests here is
 * the injected {@code array} entry rather than an application of one.) {@code SchemaDesugarer} performs that
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
        TsonCompiledVocabularyRegistry core =
                TsonCompiledVocabularyRegistry.withStandardLibrary(SchemaModelNameBinder.defaultContext(), source);
        return TsonCompiledSchemaRegistry.tree(core).get(ID);
    }

    /** The entry a name resolves to in the compiled schema -- an injected one included. */
    private static Top entry(TsonCompiledSchema compiled, String name) {
        Top definition = compiled.schema().entries().get(name);
        assertNotNull(definition, () -> "no entry '" + name + "' among " + compiled.schema().entries().keySet());
        return definition;
    }

    /** The inner container's constraints are live, not decorative: its bounds are enforced one bracket in. */
    @Test
    void theInnerContainersBoundsAreEnforcedWhenReading() {
        TsonCompiledSchema compiled = compile("  grid => [[integer; 2], text]");

        assertTrue(assertThrows(ReadException.class,
                () -> compiled.get("grid").read(TestDocuments.document("[ [1 2 3] \"a\" ]")))
                .getMessage().contains("maximum 2"));
        assertTrue(assertThrows(ReadException.class,
                () -> compiled.get("grid").read(TestDocuments.document("[ [1] \"a\" ]")))
                .getMessage().contains("minimum 2"));
    }

    /**
     * §5.3's element {@code ?} end to end, through the form the spec states the rule with: "absent elements
     * occupy positional slots -- {@code [a _ c]} has three elements and satisfies a {@code [T?; 3]} size
     * constraint". The state and both bounds land on one binding record, and the compiled reader enforces
     * exactly that reading: {@code _} is admitted at an element position and still counts toward the size.
     */
    @Test
    void aVoidableElementAdmitsTheVoidSentinelAndItCountsTowardTheSize() {
        TsonCompiledSchema compiled = compile("""
                  triple => [integer?; 3]
                  strict => [integer; 3]""");

        ArrayBody body = assertInstanceOf(ArrayBody.class, entry(compiled, "triple"));
        assertTrue(body.voidable());
        assertEquals(Optional.of(3L), body.minItems());

        assertNotNull((TsonValue) compiled.get("triple").read(TestDocuments.document("[1 _ 3]")));
        assertNotNull((TsonValue) compiled.get("triple").read(TestDocuments.document("[_ _ _]")));
        assertTrue(assertThrows(ReadException.class,
                () -> compiled.get("triple").read(TestDocuments.document("[1 _]")))
                .getMessage().contains("minimum 3"), "an absent element occupies a slot, it does not vacate one");

        assertFalse(assertInstanceOf(ArrayBody.class, entry(compiled, "strict")).voidable(),
                "the unmarked form keeps the vocabulary's own default, not voidable");
        assertTrue(assertThrows(ReadException.class,
                () -> compiled.get("strict").read(TestDocuments.document("[1 _ 3]")))
                .getMessage().contains("elements are required"));
    }

    /** An absent element reaches the tree as {@code TsonVoid}, in its own positional slot. */
    @Test
    void aVoidElementReadsAsTsonVoidInItsOwnSlot() {
        TsonCompiledSchema compiled = compile("  slots => [integer?]");

        TsonArray array = (TsonArray) compiled.get("slots").read(TestDocuments.document("[1 _ 3]"));
        assertEquals(3, array.elements().size());
        assertInstanceOf(TsonVoid.class, array.get(1));
    }

}
