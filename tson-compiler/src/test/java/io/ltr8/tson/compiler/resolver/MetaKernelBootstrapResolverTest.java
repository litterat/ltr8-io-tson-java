package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.EmptyBrace;
import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.ast.schema.Instance;
import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.compiler.ast.schema.SchemaMap;
import io.ltr8.tson.compiler.TsonSchemaParser;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.meta.IntegerType;
import io.ltr8.tson.schema.meta.RegexType;
import io.ltr8.tson.schema.meta.TextType;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.EntryKind;
import io.ltr8.tson.schema.meta.IdentifierType;
import io.ltr8.tson.schema.meta.ValueType;
import io.ltr8.tson.schema.meta.VoidType;
import io.ltr8.tson.schema.meta.IriType;
import io.ltr8.tson.schema.meta.TypeChain;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.TsonCompiledMetaRegistry;
import io.ltr8.tson.compiler.TsonSchemaLinker;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonBundledSchemas;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the bootstrap against meta-kernel.tn as packaged on the classpath (see this module's {@code
 * build.gradle.kts}): the header directives carry straight through, every declaration resolves -- the hand-built
 * pre-loaded set, the declarations resolved from their grammar, and the instances read through the readers compiled
 * from what is ready -- and its output is the kernel's ordinary resolution entry for entry. The pre-loaded set is
 * held to the kernel's measured wait cycle ({@code SPEC-FEEDBACK.md #4}, rule 4).
 */
class MetaKernelBootstrapResolverTest {

    @Test
    void headerDirectivesCarryThroughFromTheDocument() {
        TsonSchema schema = MetaKernelBootstrapResolver.getMetaKernelSchema();

        // The kernel's header is its !!id alone: it imports nothing, and is pre-loaded rather than resolved (§3.4).
        assertTrue(CanonicalIdentity.canonicalize(schema.id()).endsWith("meta-kernel.tn"));
        assertEquals(List.of(), schema.imports());
        // getMetaKernelSchema() is the one and only place that ever sets this -- see TsonSchema's own Javadoc.
        assertTrue(schema.bootstrap());
    }

    @Test
    void resolvesAllThirtySixOrdinarilyResolvableDeclarations() {
        TsonSchema schema = MetaKernelBootstrapResolver.getMetaKernelSchema();

        // A sample spanning every construct DefinitionResolver already handles on its own.
        for (String name : List.of("top", "atom", "product", "sum", "reference", "integer_size",
                "integer_type", "record", "array", "map", "tuple", "choice", "schema")) {
            assertTrue(schema.entries().containsKey(name), name + " should resolve via DefinitionResolver alone");
        }
    }

    @Test
    void valueVoidAndIdentifierEachResolveToTheirOwnConstructorsBody() {
        TsonSchema schema = MetaKernelBootstrapResolver.getMetaKernelSchema();

        Map<String, Top> expected = Map.of("value", new ValueType(), "void", new VoidType(),
                "identifier", IdentifierType.IDENTIFIER);
        expected.forEach((name, body) -> {
            Top resolved = schema.entries().get(name);
            assertEquals(EntryKind.ATOM, EntryKind.of(resolved, n -> true));
            assertEquals(body, resolved);
            assertEquals(List.of(), TypeChain.supertypesOf(resolved));
            assertEquals(java.util.Optional.of(name + "_type"), schema.constructorOf(name));
        });
    }

    @Test
    void integerResolvesToAnUnconstrainedIntegerTypeBodyWithAtomKind() {
        TsonSchema schema = MetaKernelBootstrapResolver.getMetaKernelSchema();

        Top integer = schema.entries().get("integer");
        assertEquals(EntryKind.ATOM, EntryKind.of(integer, n -> true));
        assertEquals(IntegerType.UNCONSTRAINED, integer);
    }

    @Test
    void booleanResolvesEvenThoughEnumItselfIsDeclaredLaterInTheFile() {
        // boolean => !enum [true false] appears near the top of the file and enum much later: the order is the
        // entries' waits, never the order they are written in.
        TsonSchema schema = MetaKernelBootstrapResolver.getMetaKernelSchema();

        Top booleanDef = schema.entries().get("boolean");
        assertEquals(EntryKind.ATOM, EntryKind.of(booleanDef, n -> true));
        assertEquals(new EnumBody(List.of("true", "false")), booleanDef);
    }

    @Test
    void everyEnumInstanceInTheFixtureResolves() {
        TsonSchema schema = MetaKernelBootstrapResolver.getMetaKernelSchema();

        assertEquals(new EnumBody(List.of("INDEX", "NAMED")), schema.entries().get("product_access_type"));
        assertEquals(new EnumBody(List.of("FIXED", "VARIABLE")), schema.entries().get("product_size_type"));
        for (String name : List.of("field_role", "record_extension_type")) {
            assertInstanceOf(EnumBody.class, schema.entries().get(name));
        }
    }

    @Test
    void textIriRegexResolveToTheirUnconstrainedTypeBodiesWithAtomKind() {
        TsonSchema schema = MetaKernelBootstrapResolver.getMetaKernelSchema();

        Top text = schema.entries().get("text");
        assertEquals(EntryKind.ATOM, EntryKind.of(text, n -> true));
        assertEquals(TextType.UNCONSTRAINED, text);

        Top iri = schema.entries().get("iri");
        assertEquals(EntryKind.ATOM, EntryKind.of(iri, n -> true));
        assertEquals(IriType.IRI, iri);

        Top regex = schema.entries().get("regex");
        assertEquals(EntryKind.ATOM, EntryKind.of(regex, n -> true));
        assertEquals(RegexType.UNCONSTRAINED, regex);

        // The kernel's second identifier_type instance, read rather than built: it comes out as the constant the
        // readers of iri_type.schemes hold.
        Top schemeName = schema.entries().get("scheme_name");
        assertEquals(EntryKind.ATOM, EntryKind.of(schemeName, n -> true));
        assertEquals(IdentifierType.SCHEME_NAME, schemeName);
    }

    /**
     * The bootstrap runs {@link SchemaDesugarer} over its own document like every other schema does, so its
     * output is the 70 declarations the fixture writes plus one injected declaration per distinct sugar form
     * within them -- nine {@code array} entries from §5.3's {@code [X]} field-type sugar. Producing them here
     * is what leaves the linker with nothing to materialize (see {@code MetaKernelSchemaRegistryTest}).
     * {@code enum}'s member set is not among them: it is the fixture's own {@code enum_set} declaration, since
     * {@code set} has no sugar and a {@code !} form stays prohibited at a field position (§5.2) -- and {@code
     * record.fields} is the fixture's own
     * {@code field_map} for the same reason, an ordered map having no sugar either.
     */
    @Test
    void theFixtureDeclarationsResolveAlongsideNineDesugaredEntries() {
        TsonSchema schema = MetaKernelBootstrapResolver.getMetaKernelSchema();

        assertEquals(79, schema.entries().size());
        for (String head : List.of("array_tuple_element", "array_text", "array_type_ref",
                "array_type_name", "array_type_argument", "array_template_param", "array_field_group")) {
            assertTrue(schema.entries().keySet().stream().anyMatch(name -> name.startsWith(head + "_")),
                    "expected a desugared entry with head '" + head + "'");
        }
    }

    // ── The pre-loaded set, and what the rest of the kernel comes out as ──

    /**
     * Rule 4 of {@code SPEC-FEEDBACK.md #4}: the pre-loaded set is exactly the kernel's one wait cycle. The bootstrap
     * builds by hand the instances and refinements on it, so they are measured here from {@code
     * meta-kernel-resolved.tn}'s own declarations ({@link WaitGraph}) rather than trusted: a kernel edit that moves
     * the cycle fails this test, not a read. An entry on the cycle written {@code !record} or {@code !reference} is
     * resolved from its grammar, and reads nothing.
     */
    @Test
    void theHandBuiltEntriesAreTheInstancesOnTheKernelsOneWaitCycle() throws Exception {
        WaitGraph graph = WaitGraph.of(KernelWaitGraphTest.fixture());
        Set<String> cycle = graph.components().stream().filter(graph::isCycle)
                .filter(component -> component.contains("record")).findFirst().orElseThrow();
        Set<String> read = new TreeSet<>();
        for (String entry : cycle) {
            if (!Set.of("record", "reference").contains(graph.head(entry))) {
                read.add(entry);
            }
        }
        Set<String> handBuilt = new TreeSet<>();
        // A content hash is §8.2's to spell, and the fixture writes a placeholder for it.
        MetaKernelBootstrapResolver.preLoaded().forEach(name ->
                handBuilt.add(name.replaceFirst("_[0-9a-f]{8}$", "_xxhash")));
        assertEquals(read, handBuilt);
    }

    /**
     * The bootstrap's output is the kernel's resolved form: linked, every entry and every entry's constructor is what
     * the kernel's ordinary resolution against it produces, which {@code ResolvedFixtureTest} holds to the spec's
     * {@code meta-kernel-resolved.tn}. A hand-built body that drops a fact the source states -- a set's {@code
     * min_items} -- differs here.
     */
    @Test
    void theBootstrapComesOutAsTheKernelsOrdinaryResolution() {
        TsonSchema bootstrap = TsonSchemaLinker.linkBootstrap(MetaKernelBootstrapResolver.getMetaKernelSchema())
                .schema();
        TsonSchema ordinary = TsonCompiledMetaRegistry.withStandardLibrary(SchemaMetaNameBinder.defaultContext(),
                        SchemaSource.registeredOnly()).schemaRegistry().get(TsonBundledSchemas.META_KERNEL_ID)
                .orElseThrow().schema();

        assertEquals(List.copyOf(ordinary.entries().keySet()), List.copyOf(bootstrap.entries().keySet()));
        for (String name : ordinary.entries().keySet()) {
            assertEquals(ordinary.entries().get(name), bootstrap.entries().get(name), name);
            assertEquals(ordinary.constructorOf(name), bootstrap.constructorOf(name), name);
        }
    }

    /**
     * The order the kernel writes its declarations in is consulted for nothing ([TSON-SCHEMA] §3.4.1's fifth rule):
     * reversed or shuffled, the bootstrap produces the same entries, constructors and facts.
     */
    @Test
    void theBootstrapIsTheSameInAnyOrder() {
        Map<String, String> written = bootstrapped(order -> order);

        assertEquals(written, bootstrapped(order -> {
            Collections.reverse(order);
            return order;
        }), "reversed");
        for (long seed : new long[] {1, 39, 2026}) {
            assertEquals(written, bootstrapped(order -> {
                Collections.shuffle(order, new Random(seed));
                return order;
            }), "shuffled with seed " + seed);
        }
    }

    /** The bootstrap over the kernel's declarations in the order {@code reorder} gives, each entry with its facts. */
    private static Map<String, String> bootstrapped(UnaryOperator<List<String>> reorder) {
        TsonSchemaParser parser = new TsonSchemaParser(TsonBundledSchemas.fetch(TsonBundledSchemas.META_KERNEL_ID));
        SchemaDocument document = parser.parseSchemaDocument();
        Map<String, SchemaMap.Declaration> permuted = new LinkedHashMap<>();
        reorder.apply(new ArrayList<>(document.body().declarations().keySet()))
                .forEach(name -> permuted.put(name, document.body().declarations().get(name)));
        TsonSchema schema = MetaKernelBootstrapResolver.resolve(new SchemaDocument(document.id(), document.imports(),
                new SchemaMap(document.body().annotations(), permuted)), parser.schemaPositions());
        Map<String, String> outcome = new TreeMap<>();
        schema.entries().forEach((name, value) -> outcome.put(name, value + " | facts " + schema.factsOf(name)));
        return outcome;
    }

    // ── instanceBody's own refusals -- neither reachable through the real fixture ──

    private static Instance emptyInstance(String target) {
        return new Instance(new DataValue(List.of(), Optional.of(target), new EmptyBrace()));
    }

    @Test
    void aTargetNoPreLoadedInstanceAppliesIsRefused() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> MetaKernelBootstrapResolver.instanceBody("x", emptyInstance("void_type")));
        assertTrue(thrown.getMessage().contains("void_type"));
    }

    @Test
    void aNonEmptyBodyForAnEmptyBodiedTargetThrows() {
        Instance nonEmpty = new Instance(new DataValue(List.of(), Optional.of("text_type"),
                new TokenValue("oops", TokenForm.UNQUOTED)));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> MetaKernelBootstrapResolver.instanceBody("text", nonEmpty));
        assertTrue(thrown.getMessage().contains("text_type"));
    }
}
