package io.ltr8.tson;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.ReadException;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.EntryKind;
import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.NamespaceBody;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.ValueEntry;
import io.ltr8.tson.schema.meta.ValueRef;
import io.ltr8.tson.tree.TsonMap;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A namespace type refined by {@code ^} ([TSON-SCHEMA] §5.7, §4.3): {@code meta.namespace} composes {@code meta.map},
 * so its facets take a map's kinds, and the result is a namespace type IS-A its source -- its values stand where the
 * source is wanted, a reference over the source names them, and a qualified name enters them (§5.12).
 */
class NamespaceRefinementTest {

    private static final String API = "https://example.test/api.tn";

    private static final String API_SCHEMA = """
            !!id:"https://example.test/api.tn"
            !!import:"https://tson.io/2026/40/m/meta.tn"
            !!import:"https://tson.io/2026/40/m/core.tn"
            !schema {
              method  => { verb: text }
              api     => !meta.namespace { key_type: meta.identifier  value_type: method }
              small   => !api ^ { max_items: 3 }
              tiny    => !small ^ { min_items: 1  max_items: 2 }
              holder  => { calls: api }
              narrow  => { calls: small }
              pick    => { which: meta.ref<api> }
              handler => { m: meta.ref<method> }
              kinds   => { ns: meta.ref<meta.namespace>  m: meta.ref<meta.map>  p: meta.ref<meta.product> }
              shop    => !small { get => { verb: GET }  put => { verb: PUT } }
            }""";

    private static Tson tson() {
        return Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(uri -> API_SCHEMA)));
    }

    private static String data(String body) {
        return "!!import:\"" + API + "\"\n" + body;
    }

    private static List<Diagnostic> schemaProblems(String declarations) {
        String schema = """
                !!id:"https://example.test/s.tn"
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !!import:"https://tson.io/2026/40/m/core.tn"
                !schema {
                  method  => { verb: text }
                  api     => !meta.namespace { key_type: meta.identifier  value_type: method }
                  bounded => !meta.namespace { key_type: meta.identifier  value_type: method  min_items: 1
                                               max_items: 3 }
                %s
                }""".formatted(declarations);
        return tson().validateSchema(schema);
    }

    private static void refused(String declarations, String expected) {
        List<Diagnostic> problems = schemaProblems(declarations);
        assertEquals(1, problems.size(), problems.toString());
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, problems.getFirst().code(), problems.toString());
        assertTrue(problems.getFirst().message().contains(expected), problems.getFirst().message());
    }

    // ── The refinement ───────────────────────────────────────────────────────

    @Test
    void theRefinedTypeIsANamespaceTypeWithTheSourceInItsChain() {
        TsonLinkedSchema linked = tson().bindRegistry().core().resolveLinked(API);

        NamespaceBody small = linked.namespace("small").orElseThrow();
        assertEquals(List.of("api"), small.supertypes());
        assertEquals(TypeRef.of("meta.identifier"), small.keyType());
        assertEquals(Optional.of(3L), small.maxItems());
        assertEquals(Optional.of("meta.namespace"), linked.schema().constructorOf("small"));
        assertEquals(EntryKind.PRODUCT, linked.kindOf("small"));
        NamespaceBody tiny = linked.namespace("tiny").orElseThrow();
        assertEquals(Set.of("small", "api"), Set.copyOf(tiny.supertypes()));
        assertEquals(Optional.of(1L), tiny.minItems());
        assertEquals(Optional.of(2L), tiny.maxItems());
        assertEquals(Set.of("small", "tiny"), Set.copyOf(linked.namespace("api").orElseThrow().subtypes()));
    }

    /** Read back from its resolved form under the schema's own closure, the refined type is the one resolved. */
    @Test
    void theResolvedFormIsTheNamespaceConstructorsWithTheChain() {
        Tson tson = binding();
        Top small = tson.resolve(API_SCHEMA).schema().entries().get("small");

        Map<String, Object> read = ResolvedForm.readResolved(tson, """
                !schema { small => !meta.namespace { chain: { supertypes: [api]  subtypes: [tiny] }
                                                     key_type: meta.identifier  value_type: method  max_items: 3 } }""",
                List.of(API));
        assertEquals(ResolvedForm.rendered(small), ResolvedForm.rendered(read.get("small")));
    }

    @Test
    void aBoundIsTightenedOverAStatedOneAndAFacetRestatedVerbatim() {
        assertEquals(List.of(), schemaProblems("""
                  fewer  => !bounded ^ { max_items: 2 }
                  same   => !bounded ^ { min_items: 1  max_items: 3 }
                  stated => !api ^ { key_type: meta.identifier  value_type: method  ordered: false  voidable: false }
                """));
    }

    /** A key or value type is one by the entry its name reaches, and the refined type keeps the source's spelling. */
    @Test
    void aSlotRestatedThroughAnAliasKeepsTheSourcesSpelling() {
        Tson tson = tson();
        Namespace<Top> entries = tson.resolve("""
                !!id:"https://example.test/alias.tn"
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !!import:"https://tson.io/2026/40/m/core.tn"
                !schema {
                  k2          => !api ^ { key_type: method_name }
                  kernel      => !api ^ { key_type: meta.type_name }
                  v2          => !api ^ { value_type: opal  max_items: 3 }
                  back        => !aliased ^ { key_type: meta.identifier  value_type: method }
                  method      => { verb: text }
                  method_name => meta.identifier
                  opal        => method
                  api         => !meta.namespace { key_type: meta.identifier  value_type: method }
                  aliased     => !meta.namespace { key_type: method_name  value_type: opal }
                }""").schema().entries();

        NamespaceBody k2 = assertInstanceOf(NamespaceBody.class, entries.get("k2"));
        assertEquals(TypeRef.of("meta.identifier"), k2.keyType());
        assertEquals(List.of("api"), k2.supertypes());
        assertEquals(TypeRef.of("meta.identifier"), assertInstanceOf(NamespaceBody.class, entries.get("kernel")).keyType());
        NamespaceBody v2 = assertInstanceOf(NamespaceBody.class, entries.get("v2"));
        assertEquals(TypeRef.of("method"), v2.valueType());
        assertEquals(Optional.of(3L), v2.maxItems());
        NamespaceBody back = assertInstanceOf(NamespaceBody.class, entries.get("back"));
        assertEquals(TypeRef.of("method_name"), back.keyType());
        assertEquals(TypeRef.of("opal"), back.valueType());
    }

    @Test
    void anAliasOfAnotherTypeStillReplacesASlot() {
        refused("""
                  scheme => net.scheme_name
                  r      => !api ^ { key_type: scheme }""", "key_type scheme replaces the source's own meta.identifier");
        refused("""
                  words => text
                  r     => !api ^ { value_type: words }""", "value_type words replaces the source's own method");
    }

    // ── A composition of the constructor ─────────────────────────────────────

    /**
     * {@code meta.namespace} composes {@code meta.map} and no base kind of its own ([TSON-SCHEMA] §5.5), so a composition
     * of it loads as one of {@code meta.map} does, and an application of either reports the same gap.
     */
    @Test
    void aCompositionOfTheNamespaceConstructorIsOneOfTheMapConstructor() {
        assertEquals(List.of(), schemaProblems("  my_ns => meta.namespace & { label?: text }"));
        assertEquals(List.of(), schemaProblems("  my_map => meta.map & { label?: text }"));

        List<Diagnostic> namespace = schemaProblems("""
                  my_ns => meta.namespace & { label?: text }
                  x     => !my_ns { key_type: meta.identifier  value_type: method }""");
        List<Diagnostic> map = schemaProblems("""
                  my_map => meta.map & { label?: text }
                  x      => !my_map { key_type: meta.identifier  value_type: method }""");
        for (List<Diagnostic> problems : List.of(namespace, map)) {
            assertEquals(1, problems.size(), problems.toString());
            assertEquals(Diagnostic.Code.NOT_IMPLEMENTED, problems.getFirst().code(), problems.toString());
            assertTrue(problems.getFirst().message().contains(
                    "this library models the body of a type built only by the bundled schemas' constructors"),
                    problems.toString());
        }
    }

    static Stream<Arguments> facetsMovedOutward() {
        return Stream.of(
                Arguments.of("a ceiling raised", "!bounded ^ { max_items: 4 }", "max_items 4 is above"),
                Arguments.of("a floor lowered", "!bounded ^ { min_items: 0 }", "min_items 0 is below"),
                Arguments.of("a key family replaced", "!api ^ { key_type: net.scheme_name }",
                        "only restates key_type"),
                Arguments.of("a value type replaced", "!api ^ { value_type: text }", "only restates value_type"),
                Arguments.of("order changed", "!api ^ { ordered: true }", "only restates ordered"),
                Arguments.of("voids allowed", "!api ^ { voidable: true }", "voidable"),
                Arguments.of("an unknown field", "!api ^ { note: x }", "note"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("facetsMovedOutward")
    void aRefinementMovingAFacetOutwardIsRefused(String facet, String refinement, String expected) {
        refused("  r => " + refinement, expected);
    }

    @Test
    void aNamespaceValueIsFinishedToRefinement() {
        refused("""
                  orders => !api { get => { verb: GET } }
                  r      => !orders ^ { max_items: 1 }
                """, "finished and takes no refinement");
    }

    // ── Values of the refined type ───────────────────────────────────────────

    @Test
    void aValueEntryIsJudgedUnderTheNarrowedBounds() {
        assertEquals(List.of(), schemaProblems("""
                  small => !api ^ { max_items: 2 }
                  v     => !small { a => { verb: A }  b => { verb: B } }
                """));
        refused("""
                  small => !api ^ { max_items: 2 }
                  v     => !small { a => { verb: A }  b => { verb: B }  c => { verb: C } }
                """, "more than the maximum 2");
    }

    @Test
    void aDataValueIsReadAtTheRefinedTypeInTreeAndBindMode() {
        Tson tson = tson();
        TsonMap tree = assertInstanceOf(TsonMap.class,
                tson.treeReader().read(data("!small { a => { verb: A }  b => { verb: B } }")));
        assertEquals(2, tree.entries().size());
        assertThrows(ReadException.class, () -> tson.treeReader().read(
                data("!small { a => { verb: A }  b => { verb: B }  c => { verb: C }  d => { verb: D } }")));

        Tson binding = binding();
        Map<?, ?> bound = binding.objectReader().read(data("!small { a => { verb: A } }"), Map.class);
        assertEquals(new Method("A"), bound.get("a"));
        assertThrows(ReadException.class, () -> binding.objectReader().read(
                data("!small { a => { verb: A }  b => { verb: B }  c => { verb: C }  d => { verb: D } }"),
                Map.class));
    }

    @Test
    void aValueOfTheRefinedTypeStandsWhereTheSourceIsWanted() {
        Tson tson = tson();
        assertEquals(List.of(), tson.validate(data("!holder { calls: !small { a => { verb: A } } }")));
        List<Diagnostic> over = tson.validate(data(
                "!holder { calls: !tiny { a => { verb: A }  b => { verb: B }  c => { verb: C } } }"));
        assertEquals(1, over.size(), over.toString());
        assertTrue(over.getFirst().message().contains("more than the maximum 2"), over.toString());
        assertEquals(Diagnostic.Code.TYPE_MISMATCH,
                tson.validate(data("!narrow { calls: !api { a => { verb: A } } }")).getFirst().code());
    }

    @Test
    void aReferenceOverTheSourceNamesAValueOfTheRefinedType() {
        Tson tson = tson();
        ValueRef<?> which = tson.treeReader().withSchema(API).read(data("!pick { which: shop }")).get("which")
                .as(ValueRef.class).orElseThrow();
        assertEquals("shop", which.name());
        assertEquals(List.of(), tson.validate(data("!pick { which: shop }")));
    }

    @Test
    void aQualifiedNameEntersAValueOfTheRefinedType() {
        Tson tson = tson();
        assertEquals(List.of(), tson.validate(data("!handler { m: shop.get }")));
        ValueRef<?> m = tson.treeReader().read(data("!handler { m: shop.put }")).get("m")
                .as(ValueRef.class).orElseThrow();
        assertEquals("PUT", ((TsonValue) m.get()).get("verb").asString().orElseThrow());
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION,
                tson.validate(data("!handler { m: shop.delete }")).getFirst().code());
    }

    @Test
    void theKindsAdmitTheRefinedType() {
        assertEquals(List.of(), tson().validate(data("!kinds { ns: small  m: tiny  p: small }")));
    }

    @Test
    void aValueEntryOfTheRefinedTypeWritesAndReadsBack() {
        Tson tson = binding();
        ValueEntry shop = assertInstanceOf(ValueEntry.class, tson.resolve(API_SCHEMA).schema().entries().get("shop"));
        String written = tson.objectWriter().toTson(shop);
        assertEquals("!small { get => { verb: \"GET\" } put => { verb: \"PUT\" } }", written.replaceAll("\\s+", " "));

        Namespace<?> back = tson.objectReader().withSchema(API).readAs(written, "small", Namespace.class);
        assertEquals(shop.value(), back);
    }

    public record Method(String verb) {
    }

    private static Tson binding() {
        return Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(uri -> API_SCHEMA))
                .withDataBindContext(DataBindContext.builder()
                        .nameBinder(DataNameBinder.ofMap(Map.of("method", Method.class))
                                .orElse(SchemaModelNameBinder.INSTANCE))
                        .registerAtoms(AtomContext.hostTypes()).build()));
    }
}
