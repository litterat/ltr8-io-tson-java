package io.ltr8.tson.compiler;

import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.TypeChain;

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
 * result<T>} denotes no entry -- a template is not a type (§5.10) -- so there is nothing to name in a chain.
 * What the held body keeps is the application itself as a written parent, {@code parents} being a
 * {@code [type_kind]} slot whose reference may carry arguments, and materialisation substitutes and closes it
 * with the rest of the held body.
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
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
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
        TsonCompiledMetaRegistry core =
                TsonCompiledMetaRegistry.withStandardLibrary(SchemaMetaNameBinder.defaultContext(), source);
        return TsonCompiledSchemaRegistry.tree(core).get(ID);
    }

    /**
     * The entry a {@code name => head<args>} declaration denotes: itself, since such a declaration <em>is</em>
     * its instantiation (§8.2), or the entry it aliases where an earlier declaration
     * already named the same application.
     */
    private static String target(TsonCompiledSchema compiled, String alias) {
        return compiled.schema().entries().get(alias) instanceof io.ltr8.tson.schema.meta.Alias ref
                ? ref.target().name() : alias;
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
                TypeChain.subtypesOf(compiled.schema().entries().get(base)),
                () -> base + ": " + TypeChain.subtypesOf(compiled.schema().entries().get(base)));
        // Each is also a declared member of its own template's family (§8.2's entry shape), and the chain is
        // complete: IS-A result_of, which is a member of result's family, so IS-A result too.
        assertEquals(List.of("ok", "result", base),
                TypeChain.supertypesOf(compiled.schema().entries().get(target(compiled, "ok_of"))));
        assertEquals(List.of("err", "result", base),
                TypeChain.supertypesOf(compiled.schema().entries().get(target(compiled, "err_of"))));
    }

    /**
     * <b>A family-base template is IS-A its parents, and so are its instantiations</b> ([TSON-SCHEMA] §4.1). A
     * record template carries a chain: it holds {@code base} as written and as reached, which puts the
     * template in {@code base.subtypes} as any record composing {@code base} is, and every instantiation closing
     * from it completes the same parents into a chain naming {@code base}.
     */
    @Test
    void aFamilyBaseTemplatesInstantiationsAreIndexedUnderItsBase() {
        TsonCompiledSchema compiled = compile("""
                  base     => { x: int32 }
                  box      => <V> base & { item: V }
                  box_text => box<text>
                """);
        Top baseEntry = compiled.schema().entries().get("base");

        assertTrue(TypeChain.subtypesOf(baseEntry).contains(target(compiled, "box_text")),
                () -> "the instantiation is indexed: " + TypeChain.subtypesOf(baseEntry));
        assertTrue(TypeChain.subtypesOf(baseEntry).contains("box"),
                () -> "the template is IS-A its parent too: " + TypeChain.subtypesOf(baseEntry));
        assertEquals(List.of(TypeRef.of("base")), TypeChain.parentsOf(compiled.schema().entries().get("box")),
                "the open record keeps its parent as written, which closing completes");
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
        assertTrue(TypeChain.subtypesOf(compiled.schema().entries().get(intBase)).contains(target(compiled, "ok_int")));
        assertFalse(TypeChain.subtypesOf(compiled.schema().entries().get(intBase)).contains(target(compiled, "ok_of")),
                () -> intBase + ": " + TypeChain.subtypesOf(compiled.schema().entries().get(intBase)));
        assertFalse(TypeChain.subtypesOf(compiled.schema().entries().get(textBase)).contains(target(compiled, "ok_int")),
                () -> textBase + ": " + TypeChain.subtypesOf(compiled.schema().entries().get(textBase)));
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

        assertEquals(List.of("great", "ok", target(compiled, "ok_of"), "result", target(compiled, "result_of")),
                TypeChain.supertypesOf(compiled.schema().entries().get(target(compiled, "great_of"))),
                "the direct parent, then the chain it already carried, complete");
        assertTrue(TypeChain.subtypesOf(compiled.schema().entries().get(target(compiled, "result_of")))
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

        assertEquals(List.of("ok", "result", target(compiled, "result_of"), "tagged"),
                TypeChain.supertypesOf(compiled.schema().entries().get(target(compiled, "ok_of"))),
                "tagged arrives with the template's own chain; the instantiation is minted here");
    }

    /**
     * §5.9 against a subtype template. Subtraction revokes IS-A for every parent, so the closed entry gets no
     * edge to it -- and the application is not kept either: a subtracted record is a record with an empty chain,
     * and an application kept would close into a live edge one pass later.
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

        assertEquals(List.of("payload"), ((RecordBody) compiled.schema().entries().get(thin)).fields()
                .keySet().stream().toList(), "the subtraction really ran");
        // Not IS-A result: the only edge is to thin, whose declared member thin_of is (§8.2's entry shape).
        assertEquals(List.of("thin"), ((RecordBody) compiled.schema().entries().get(thin)).supertypes(),
                "contract: broken, and no application left in the chain to close into an edge");
        assertEquals(List.of(), TypeChain.subtypesOf(compiled.schema().entries().get(target(compiled, "result_of"))));
    }

    /** The template itself is untouched: it has no instantiation of its parent to be IS-A. */
    @Test
    void theOpenTemplateNamesNoParentInItsOwnContractIndex() {
        TsonCompiledSchema compiled = compile(FAMILY);

        Top ok = compiled.schema().entries().get("ok");
        assertEquals(List.of(), TypeChain.supertypesOf(ok),
                () -> "result is a template and nothing is IS-A one: " + TypeChain.supertypesOf(ok));
    }
}
