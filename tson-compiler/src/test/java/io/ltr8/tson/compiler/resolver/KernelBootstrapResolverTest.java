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
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.numeric.IntegerType;
import io.ltr8.tson.schema.unicode.RegexType;
import io.ltr8.tson.schema.unicode.TextType;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.EntryKind;
import io.ltr8.tson.schema.unicode.IdentifierType;
import io.ltr8.tson.schema.meta.VoidType;
import io.ltr8.tson.schema.net.IriType;
import io.ltr8.tson.schema.meta.TypeChain;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.TsonCompiledVocabularyRegistry;
import io.ltr8.tson.compiler.TsonSchemaLinker;
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
import io.ltr8.tson.schema.SchemaPaths;
import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.ValueEntry;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the bootstrap against the kernel, meta.tn, as packaged on the classpath (see this module's {@code
 * build.gradle.kts}): the header directives carry straight through, every declaration resolves -- the hand-built
 * pre-loaded set, the declarations resolved from their grammar, and the instances read through the readers compiled
 * from what is ready -- and its output is the kernel's ordinary resolution entry for entry. The pre-loaded set is
 * held to the kernel's measured wait cycle ([TSON-SCHEMA] §3.4, §3.4.1 rule 4).
 */
class KernelBootstrapResolverTest {

    @Test
    void headerDirectivesCarryThroughFromTheDocument() {
        TsonSchema schema = KernelBootstrapResolver.kernelSchema();

        // The kernel's header is its !!id alone: it imports nothing, and is pre-loaded rather than resolved (§3.4).
        assertTrue(CanonicalIdentity.canonicalize(schema.id()).endsWith("/m/meta.tn"));
        assertEquals(List.of(), schema.imports());
        // kernelSchema() is the one and only place that ever sets this -- see TsonSchema's own Javadoc.
        assertTrue(schema.bootstrap());
    }

    @Test
    void resolvesTheOrdinarilyResolvableDeclarations() {
        TsonSchema schema = KernelBootstrapResolver.kernelSchema();

        // A sample spanning every construct DefinitionResolver already handles on its own.
        for (String name : List.of("meta.top", "meta.atom", "meta.product", "meta.sum", "meta.alias",
                "numeric.integer_size", "numeric.integer_type", "meta.record", "meta.array", "meta.namespace", "meta.schema",
                "meta.type_name", "meta.declared", "meta.map", "meta.tuple", "meta.choice", "meta.param")) {
            assertTrue(schema.entries().containsKey(name), name + " should resolve via DefinitionResolver alone");
        }
    }

    @Test
    void voidAndIdentifierEachResolveToTheirOwnConstructorsBody() {
        TsonSchema schema = KernelBootstrapResolver.kernelSchema();

        Map<String, Top> expected = Map.of("void", new VoidType(), "meta.identifier", IdentifierType.IDENTIFIER);
        Map<String, String> constructors = Map.of("void", "meta.void_type", "meta.identifier",
                "unicode.identifier_type");
        expected.forEach((name, body) -> {
            Top resolved = schema.entries().get(name);
            assertEquals(EntryKind.ATOM, EntryKind.of(resolved, n -> true));
            assertEquals(body, resolved);
            assertEquals(List.of(), TypeChain.supertypesOf(resolved));
            assertEquals(java.util.Optional.of(constructors.get(name)), schema.constructorOf(name));
        });
    }

    @Test
    void integerResolvesToAnUnconstrainedIntegerTypeBodyWithAtomKind() {
        TsonSchema schema = KernelBootstrapResolver.kernelSchema();

        Top integer = schema.entries().get("integer");
        assertEquals(EntryKind.ATOM, EntryKind.of(integer, n -> true));
        assertEquals(IntegerType.UNCONSTRAINED, integer);
    }

    @Test
    void booleanResolvesEvenThoughEnumItselfIsDeclaredLaterInTheFile() {
        // boolean => !enum [true false] appears near the top of the file and enum much later: the order is the
        // entries' waits, never the order they are written in.
        TsonSchema schema = KernelBootstrapResolver.kernelSchema();

        Top booleanDef = schema.entries().get("boolean");
        assertEquals(EntryKind.ATOM, EntryKind.of(booleanDef, n -> true));
        assertEquals(new EnumBody(List.of("true", "false")), booleanDef);
    }

    @Test
    void everyEnumInstanceInTheFixtureResolves() {
        TsonSchema schema = KernelBootstrapResolver.kernelSchema();

        assertEquals(new EnumBody(List.of("INDEX", "NAMED")), schema.entries().get("meta.product_access_type"));
        assertEquals(new EnumBody(List.of("FIXED", "VARIABLE")), schema.entries().get("meta.product_size_type"));
        for (String name : List.of("meta.field_role", "meta.record_extension_type")) {
            assertInstanceOf(EnumBody.class, schema.entries().get(name));
        }
    }

    @Test
    void textIriRegexResolveToTheirUnconstrainedTypeBodiesWithAtomKind() {
        TsonSchema schema = KernelBootstrapResolver.kernelSchema();

        Top text = schema.entries().get("text");
        assertEquals(EntryKind.ATOM, EntryKind.of(text, n -> true));
        assertEquals(TextType.UNCONSTRAINED, text);

        Top iri = schema.entries().get("iri");
        assertEquals(EntryKind.ATOM, EntryKind.of(iri, n -> true));
        assertEquals(IriType.IRI, TypeChain.withoutChain(iri));
        assertEquals(List.of("iri_reference"), ((IriType) iri).chain().supertypes(), "an iri IS-A iri_reference");

        Top regex = schema.entries().get("regex");
        assertEquals(EntryKind.ATOM, EntryKind.of(regex, n -> true));
        assertEquals(RegexType.UNCONSTRAINED, regex);

        // The kernel's second identifier_type instance, read rather than built: it comes out as the constant the
        // readers of iri_type.schemes hold.
        Top schemeName = schema.entries().get("net.scheme_name");
        assertEquals(EntryKind.ATOM, EntryKind.of(schemeName, n -> true));
        assertEquals(IdentifierType.SCHEME_NAME, schemeName);
    }

    /**
     * The bootstrap runs {@link SchemaDesugarer} over its own document like every other schema does, and the phase
     * adds no declaration: each {@code [X]} at a field's type is the construction written in place there, so the
     * output is the kernel's 77 declarations -- its six maps' members and the root's own -- and the six maps, and
     * {@code record.parents} holds its array in place.
     */
    @Test
    void theFixtureDeclarationsResolveWithNoEntryAdded() {
        TsonSchema schema = KernelBootstrapResolver.kernelSchema();

        assertEquals(82, schema.entries().size());
        io.ltr8.tson.schema.meta.RecordBody chain =
                (io.ltr8.tson.schema.meta.RecordBody) schema.entries().get("meta.type_chain");
        assertEquals(new io.ltr8.tson.schema.meta.TypeInline(io.ltr8.tson.schema.meta.ArrayBody.of(
                io.ltr8.tson.schema.meta.TypeRef.of("meta.type_ref"))), chain.fields().get("parents").type());
    }

    // ── The pre-loaded set, and what the rest of the kernel comes out as ──

    /**
     * [TSON-SCHEMA] §3.4, §3.4.1's rule 4: the pre-loaded set is exactly the kernel's one wait cycle. The bootstrap
     * builds by hand the instances and refinements on it, so they are measured here from {@code
     * meta-resolved.tn}'s own declarations ({@link WaitGraph}) rather than trusted: a kernel edit that moves
     * the cycle fails this test, not a read. An entry on the cycle written {@code !meta.record} or {@code
     * !meta.alias} is
     * resolved from its grammar, and reads nothing.
     */
    @Test
    void theHandBuiltEntriesAreTheInstancesOnTheKernelsOneWaitCycle() throws Exception {
        WaitGraph graph = WaitGraph.of(KernelWaitGraphTest.fixture());
        Set<String> cycle = graph.components().stream().filter(graph::isCycle)
                .filter(component -> component.contains("meta.record")).findFirst().orElseThrow();
        Set<String> read = new TreeSet<>();
        for (String entry : cycle) {
            if (!Set.of("meta.record", "meta.alias").contains(graph.head(entry))) {
                read.add(entry);
            }
        }
        assertEquals(read, new TreeSet<>(KernelBootstrapResolver.preLoaded()));
    }

    /**
     * The bootstrap's output is the kernel's resolved form: linked, every entry and every entry's constructor is what
     * the kernel's ordinary resolution against it produces, which {@code ResolvedFixtureTest} holds to the spec's
     * {@code meta-resolved.tn}. A hand-built body that drops a fact the source states -- a set's {@code
     * min_items} -- differs here.
     */
    @Test
    void theBootstrapComesOutAsTheKernelsOrdinaryResolution() {
        TsonSchema bootstrap = TsonSchemaLinker.linkBootstrap(KernelBootstrapResolver.kernelSchema())
                .schema();
        TsonSchema ordinary = TsonCompiledVocabularyRegistry.withStandardLibrary(SchemaModelNameBinder.defaultContext(),
                        SchemaSource.registeredOnly()).schemaRegistry().get(TsonBundledSchemas.META_ID)
                .orElseThrow().schema();

        assertEquals(List.copyOf(ordinary.entries().keySet()), List.copyOf(bootstrap.entries().keySet()));
        for (String name : ordinary.entries().keySet()) {
            if (SchemaPaths.isNestedMap(ordinary.entries().get(name))) {
                continue; // the map `meta` is derived from its members' entries, each compared on its own
            }
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
        TsonSchemaParser parser = new TsonSchemaParser(TsonBundledSchemas.fetch(TsonBundledSchemas.META_ID));
        SchemaDocument document = parser.parseSchemaDocument();
        Map<String, SchemaMap.Declaration> permuted = new LinkedHashMap<>();
        reorder.apply(new ArrayList<>(document.body().declarations().keySet()))
                .forEach(name -> permuted.put(name, document.body().declarations().get(name)));
        TsonSchema schema = KernelBootstrapResolver.resolve(new SchemaDocument(document.id(), document.imports(),
                new SchemaMap(document.body().annotations(), permuted)), parser.schemaPositions());
        Map<String, String> outcome = new TreeMap<>();
        // A nested map is derived from its members' entries, in the order they are declared: its names are compared.
        schema.entries().forEach((name, value) -> outcome.put(name, (SchemaPaths.isNestedMap(value)
                ? new TreeSet<>(((ValueEntry) value).value() instanceof Namespace<?> members ? members.keySet()
                        : Set.of()).toString() : value.toString()) + " | facts " + schema.factsOf(name)));
        return outcome;
    }

    // ── instanceBody's own refusals -- neither reachable through the real fixture ──

    private static Instance emptyInstance(String target) {
        return new Instance(new DataValue(List.of(), Optional.of(target), new EmptyBrace()));
    }

    @Test
    void aTargetNoPreLoadedInstanceAppliesIsRefused() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> KernelBootstrapResolver.instanceBody("x", emptyInstance("meta.record")));
        assertTrue(thrown.getMessage().contains("meta.record"));
    }

    @Test
    void aNonEmptyBodyForAnEmptyBodiedTargetThrows() {
        Instance nonEmpty = new Instance(new DataValue(List.of(), Optional.of("unicode.text_type"),
                new TokenValue("oops", TokenForm.UNQUOTED)));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> KernelBootstrapResolver.instanceBody("text", nonEmpty));
        assertTrue(thrown.getMessage().contains("unicode.text_type"));
    }
}
