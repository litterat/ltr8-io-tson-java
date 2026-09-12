package io.ltr8.tson.compiler;

import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A family of <b>subtype templates</b> over one base template -- {@code result<T>} with {@code ok<T>} and
 * {@code err<T>} composing it -- closed at an argument. The edge each instantiation gets is to the base
 * <em>at the same argument</em>, which is the whole claim: {@code ok<text>} is IS-A {@code result<text>} and
 * has nothing to do with {@code result<int32>}.
 *
 * <p><b>The edge cannot be minted where the author writes it.</b> At {@code ok}'s declaration {@code
 * result<T>} denotes no entry -- a template is not a type (§5.10) -- so the contract index has nothing to
 * name. What the declaration keeps is the application itself, {@code record.supertypes} being a
 * {@code [type_ref]} channel, and materialisation substitutes and closes it with the rest of the held body.
 *
 * <p><b>Why a reference and not the head name.</b> §5.8 describes {@code supertypes} as recording head names
 * only, with substitutability then a two-part check against the bodies. A head name cannot work here: no
 * position is ever typed {@code result}, so the edge would point at something nothing is declared as, and it
 * would hold for every instantiation at once -- {@code ok<int32>} at a {@code result<text>} position included.
 * Carrying the arguments makes the index answer on its own, which is what every reader already consults.
 */
class SubtypeTemplateFamilyTest {

    private static final String ID = "https://example.test/subtype-template.tn";

    private static TsonCompiledSchema compile(String declarations) {
        String schema = """
                !!id:"https://example.test/subtype-template.tn"
                !!meta:"https://tson.io/2026/36/m/meta.tn"
                !!import:"https://tson.io/2026/36/m/core.tn"
                {
                %s
                }
                """.formatted(declarations);
        SchemaSource source = uri -> {
            if (CanonicalIdentity.sameIdentity(uri, ID)) {
                return schema;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        TsonCompiledMetaRegistry core =
                TsonCompiledMetaRegistry.withStandardLibrary(SchemaMetaNameBinder.defaultContext(), source);
        return TsonCompiledSchemaRegistry.tree(core).get(ID);
    }

    /** The entry a `name => head<args>` alias resolves to -- what every use site flattens to. */
    private static String target(TsonCompiledSchema compiled, String alias) {
        return compiled.schema().entries().get(alias).source().orElseThrow().name();
    }

    private static final String FAMILY = """
              result    => <T> { payload: T }
              ok        => <T> result<T> & { note: text }
              err       => <T> result<T> & { reason: text }
              result_of => result<text>
              ok_of     => ok<text>
              err_of    => err<text>
            """;

    @Test
    void bothSubtypeInstantiationsAreIndexedUnderTheBaseAtTheSameArgument() {
        TsonCompiledSchema compiled = compile(FAMILY);
        String base = target(compiled, "result_of");

        assertEquals(List.of(target(compiled, "ok_of"), target(compiled, "err_of")),
                compiled.schema().entries().get(base).subtypes(),
                () -> base + ": " + compiled.schema().entries().get(base).subtypes());
        assertEquals(List.of(base), compiled.schema().entries().get(target(compiled, "ok_of")).supertypes());
        assertEquals(List.of(base), compiled.schema().entries().get(target(compiled, "err_of")).supertypes());
    }

    /**
     * Two arguments, two families. Nothing in {@code result<int32>}'s family came from {@code text}'s, which
     * is the fact a name-level edge to {@code result} could not have carried.
     */
    @Test
    void anotherArgumentIsAnotherFamilyEntirely() {
        TsonCompiledSchema compiled = compile(FAMILY + """
                      result_int => result<int32>
                      ok_int     => ok<int32>
                """);
        String textBase = target(compiled, "result_of");
        String intBase = target(compiled, "result_int");

        assertFalse(textBase.equals(intBase), "two instantiations, two entries");
        assertTrue(compiled.schema().entries().get(intBase).subtypes().contains(target(compiled, "ok_int")));
        assertFalse(compiled.schema().entries().get(intBase).subtypes().contains(target(compiled, "ok_of")),
                () -> intBase + ": " + compiled.schema().entries().get(intBase).subtypes());
        assertFalse(compiled.schema().entries().get(textBase).subtypes().contains(target(compiled, "ok_int")),
                () -> textBase + ": " + compiled.schema().entries().get(textBase).subtypes());
    }

    /** Three levels deep, each hop minted as its own instantiation closes -- the chain is transitive. */
    @Test
    void aChainOfSubtypeTemplatesAccumulatesTheWholeContract() {
        TsonCompiledSchema compiled = compile("""
                  result   => <T> { payload: T }
                  ok       => <T> result<T> & { note: text }
                  great    => <T> ok<T> & { stars: int32 }
                  result_of => result<text>
                  ok_of     => ok<text>
                  great_of  => great<text>
                """);

        assertEquals(List.of(target(compiled, "ok_of"), target(compiled, "result_of")),
                compiled.schema().entries().get(target(compiled, "great_of")).supertypes(),
                "the direct parent, then the chain it already carried");
        assertTrue(compiled.schema().entries().get(target(compiled, "result_of")).subtypes()
                .contains(target(compiled, "great_of")), "and the base indexes the grandchild");
    }

    /** The base's own concrete ancestors still come through, alongside the closed application. */
    @Test
    void aConcreteAncestorOfTheBaseIsCarriedBesideTheClosedApplication() {
        TsonCompiledSchema compiled = compile("""
                  tagged    => { tag: text }
                  result    => <T> tagged & { payload: T }
                  ok        => <T> result<T> & { note: text }
                  result_of => result<text>
                  ok_of     => ok<text>
                """);

        assertEquals(List.of("tagged", target(compiled, "result_of")),
                compiled.schema().entries().get(target(compiled, "ok_of")).supertypes(),
                "tagged arrives with the template's own chain; the instantiation is minted here");
    }

    /**
     * §5.9 against a subtype template. Subtraction revokes IS-A for every parent, so the closed entry gets no
     * edge -- and the application is not kept as lineage either, where a name would be: a name in a body whose
     * contract was emptied is inert, and an application closes into a live edge one pass later.
     */
    @Test
    void subtractionRevokesTheEdgeAnApplicationWouldOtherwiseMint() {
        TsonCompiledSchema compiled = compile("""
                  result    => <T> { payload: T  scratch: text }
                  thin      => <T> result<T> - { scratch }
                  result_of => result<text>
                  thin_of   => thin<text>
                """);
        String thin = target(compiled, "thin_of");

        assertEquals(List.of("payload"), ((RecordBody) compiled.schema().entries().get(thin).body()).fields()
                .stream().map(f -> f.name()).toList(), "the subtraction really ran");
        assertEquals(List.of(), compiled.schema().entries().get(thin).supertypes(), "contract: broken");
        assertEquals(List.of(), ((RecordBody) compiled.schema().entries().get(thin).body()).supertypes(),
                "and nothing left in the body to close into one");
        assertEquals(List.of(), compiled.schema().entries().get(target(compiled, "result_of")).subtypes());
    }

    /** The template itself is untouched: it has no instantiation of its parent to be IS-A. */
    @Test
    void theOpenTemplateNamesNoParentInItsOwnContractIndex() {
        TsonCompiledSchema compiled = compile(FAMILY);

        TypeDefinition ok = compiled.schema().entries().get("ok");
        assertEquals(List.of(), ok.supertypes(),
                () -> "result is a template and nothing is IS-A one: " + ok.supertypes());
    }
}
