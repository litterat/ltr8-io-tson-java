package io.ltr8.tson.parser.resolver.schema;

import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.parser.Parser;
import io.ltr8.tson.parser.SchemaParser;
import io.ltr8.tson.parser.ast.schema.SchemaDocument;
import io.ltr8.tson.parser.resolver.TsonAtomContext;
import io.ltr8.tson.parser.resolver.schema.compiled.ParserFactoryRegistry;
import io.ltr8.tson.parser.resolver.schema.compiled.TsonCompiledRegistry;
import io.ltr8.tson.parser.resolver.schema.compiled.TsonSchemaParser;
import io.ltr8.tson.schema.MetaSchema;
import io.ltr8.tson.schema.SchemaRegistry;
import io.ltr8.tson.schema.SchemaValidationException;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.Unit;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves {@link SchemaResolver#compiledMetaSchema}/{@link SchemaResolver#resolveAll(SchemaDocument)}
 * -- a new resolver, given a {@link SchemaCoordinator} that already has meta-kernel and meta.tn1
 * registered and compiled, can look up core.tn1's own real {@code !!meta} target (meta.tn1) and get
 * back its *compiled* reader, genuinely usable to read real data -- not merely present. Deliberately
 * doesn't touch {@code bindAtomInstance} at all (see that method's own Javadoc for why that's a
 * separate, later step); this only proves the wiring to *reach* a compiled governing schema works.
 */
class SchemaResolverCompiledMetaSchemaTest {

    private static String readBundledCoreSource() {
        try (InputStream in = SchemaResolverCompiledMetaSchemaTest.class.getResourceAsStream("/core.tn1")) {
            if (in == null) {
                throw new IOException("core.tn1 not found on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Now that {@code bindAtomInstance} needs a real, object-binding-mode compiled reader for the
     * schema it's resolving against (see {@code SchemaResolver}'s own field), meta.tn1 itself can no
     * longer be loaded via a bare DOM-mode registry -- its own Instance declarations (e.g. {@code
     * binary_encoding => !enum [...]}) go through {@code resolveInstance}/{@code bindAtomInstance}
     * just like any other schema's. Materialize meta-kernel through a throwaway {@code
     * SchemaRegistry} first (object mode's own {@code ParserFactoryRegistry} needs a materialized
     * schema to validate against up front), build the object-mode registry from it, pre-register
     * meta-kernel so the coordinator's own bootstrap special-case is never reached, then resolve
     * meta.tn1 itself via {@link BundledSchemaSource} -- {@link DefaultSchemaCoordinator#resolve}'s
     * own generic fetch-parse-resolve-register-compile path, not a hand-rolled duplicate of it (the
     * now-deleted {@code MetaTn1Parser} was exactly that duplicate; this is what replaced it).
     */
    private static DefaultSchemaCoordinator loadMetaKernelAndMeta() {
        MetaSchema metaKernel = MetaKernelParser.parse();
        TsonSchema materializedMetaKernel = new SchemaRegistry().register(metaKernel);
        DataBindContext context = TsonAtomContext.defaultContext();
        ParserFactoryRegistry objectFactories = ParserFactoryRegistry.object(materializedMetaKernel, context);

        TsonCompiledRegistry registry = new TsonCompiledRegistry(objectFactories);
        registry.register(metaKernel);
        DefaultSchemaCoordinator coordinator = new DefaultSchemaCoordinator(registry, BundledSchemaSource.INSTANCE);

        coordinator.resolve(BundledSchemaSource.META_TN1_ID);

        return coordinator;
    }

    @Test
    void coreTn1sOwnMetaTargetResolvesToMetaTn1sCompiledReader() {
        SchemaResolver resolver = new SchemaResolver(loadMetaKernelAndMeta());
        SchemaDocument coreDocument = new SchemaParser(readBundledCoreSource()).parseSchemaDocument();

        assertEquals("https://tson.io/2026/32/m/meta.tn1", coreDocument.meta());

        TsonSchemaParser compiledMeta = resolver.compiledMetaSchema(coreDocument);

        assertTrue(compiledMeta.schema().entries().containsKey("binary_encoding"));
    }

    @Test
    void theCompiledMetaSchemaGenuinelyReadsRealData() {
        SchemaResolver resolver = new SchemaResolver(loadMetaKernelAndMeta());
        SchemaDocument coreDocument = new SchemaParser(readBundledCoreSource()).parseSchemaDocument();

        TsonSchemaParser compiledMeta = resolver.compiledMetaSchema(coreDocument);
        Object result = compiledMeta.get("binary_encoding")
                .read(new Parser("BASE64").parseDocument().root());

        assertEquals("BASE64", result);
    }

    @Test
    void withNoCoordinatorCompiledMetaSchemaThrowsClearly() {
        SchemaResolver resolver = new SchemaResolver();
        SchemaDocument coreDocument = new SchemaParser(readBundledCoreSource()).parseSchemaDocument();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> resolver.compiledMetaSchema(coreDocument));
        assertTrue(thrown.getMessage().contains("SchemaCoordinator"));
    }

    @Test
    void aCoordinatorThatNeverGotMetaTn1RegisteredThrowsClearly() {
        TsonCompiledRegistry registry = new TsonCompiledRegistry(ParserFactoryRegistry.dom());
        registry.register(MetaKernelParser.parse()); // meta-kernel only -- no meta.tn1
        SchemaResolver resolver = new SchemaResolver(new DefaultSchemaCoordinator(registry));
        SchemaDocument coreDocument = new SchemaParser(readBundledCoreSource()).parseSchemaDocument();

        // meta.tn1 isn't meta-kernel's own well-known bootstrap case, and the default SchemaSource
        // fetches nothing -- so this is exactly SchemaSource.registeredOnly()'s own rejection.
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> resolver.compiledMetaSchema(coreDocument));
        assertTrue(thrown.getMessage().contains("meta.tn1"));
        assertTrue(thrown.getMessage().contains("no fetch capability"));
    }

    // ── resolveAll(SchemaDocument)'s own validate-then-derive behavior ──

    private static final String MINI_DOCUMENT = """
            !!id:"https://example.test/mini.tn1"
            !!meta:"https://tson.io/2026/32/m/meta.tn1"
            {
              void => !unit {}
            }
            """;

    @Test
    void resolveAllDerivesStructureNamespaceFromTheCoordinatorAutomatically() {
        SchemaResolver resolver = new SchemaResolver(loadMetaKernelAndMeta());
        SchemaDocument miniDocument = new SchemaParser(MINI_DOCUMENT).parseSchemaDocument();

        // "unit" is neither local to mini.tn1 nor imported by it -- only reachable if resolveAll
        // itself derived the structure namespace from the coordinator's own meta.tn1 entry (which in
        // turn carries meta-kernel's own entries, merged in via meta.tn1's real !!import).
        TsonSchema resolved = resolver.resolveAll(miniDocument);

        TypeDefinition voidDef = resolved.entries().get("void");
        assertEquals(new Unit(), voidDef.body());
    }

    @Test
    void resolveAllThrowsClearlyWhenTheMetaTargetCantBeResolvedAtAll() {
        TsonCompiledRegistry registry = new TsonCompiledRegistry(ParserFactoryRegistry.dom());
        SchemaResolver resolver = new SchemaResolver(new DefaultSchemaCoordinator(registry));
        SchemaDocument miniDocument = new SchemaParser(MINI_DOCUMENT).parseSchemaDocument();

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> resolver.resolveAll(miniDocument));
        assertTrue(thrown.getMessage().contains("meta.tn1"));
    }

    private static final String MINI_DOCUMENT_NO_ID = """
            !!meta:"https://tson.io/2026/32/m/meta.tn1"
            {
              void => !unit {}
            }
            """;

    @Test
    void resolveAllThrowsClearlyWhenIdIsAbsent() {
        SchemaResolver resolver = new SchemaResolver(loadMetaKernelAndMeta());
        SchemaDocument noIdDocument = new SchemaParser(MINI_DOCUMENT_NO_ID).parseSchemaDocument();

        assertTrue(noIdDocument.id().isEmpty());
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> resolver.resolveAll(noIdDocument));
        assertTrue(thrown.getMessage().contains("!!id"));
        assertTrue(thrown.getMessage().contains("absent"));
    }

    private static final String MINI_DOCUMENT_MALFORMED_ID = """
            !!id:"mini.tn1"
            !!meta:"https://tson.io/2026/32/m/meta.tn1"
            {
              void => !unit {}
            }
            """;

    @Test
    void resolveAllThrowsClearlyWhenIdIsNotAValidCanonicalIdentity() {
        SchemaResolver resolver = new SchemaResolver(loadMetaKernelAndMeta());
        SchemaDocument malformedIdDocument = new SchemaParser(MINI_DOCUMENT_MALFORMED_ID).parseSchemaDocument();

        // "mini.tn1" alone is a syntactically valid relative-reference URI, but has no scheme --
        // CanonicalIdentity.of's own rejection, surfaced here via SchemaRegistry.validateIdentity.
        assertThrows(SchemaValidationException.class, () -> resolver.resolveAll(malformedIdDocument));
    }

    private static final String MINI_DOCUMENT_MALFORMED_IMPORT = """
            !!id:"https://example.test/mini.tn1"
            !!meta:"https://tson.io/2026/32/m/meta.tn1"
            !!import:"meta-kernel.tn1"
            {
              void => !unit {}
            }
            """;

    @Test
    void resolveAllThrowsClearlyWhenAnImportUriIsNotAValidCanonicalIdentity() {
        SchemaResolver resolver = new SchemaResolver(loadMetaKernelAndMeta());
        SchemaDocument malformedImportDocument = new SchemaParser(MINI_DOCUMENT_MALFORMED_IMPORT).parseSchemaDocument();

        assertEquals(1, malformedImportDocument.imports().size());
        // "meta-kernel.tn1" alone is a syntactically valid relative-reference URI, but has no scheme.
        assertThrows(SchemaValidationException.class, () -> resolver.resolveAll(malformedImportDocument));
    }

    private static final String MINI_DOCUMENT_IMPORT_MERGED = """
            !!id:"https://example.test/mini.tn1"
            !!meta:"https://tson.io/2026/32/m/meta.tn1"
            !!import:"https://tson.io/2026/32/m/meta-kernel.tn1"
            {
              my_type => unit & {}
            }
            """;

    @Test
    void resolveAllGenuinelyMergesImportedEntriesIntoTheTypeNameNamespace() {
        // A bare type-ref (§8.3) is carried through unverified regardless of whether the target
        // exists anywhere, so that alone wouldn't prove anything -- composition is the real test:
        // resolveComposition does exactly one resolved.get(supertypeName), no fallback, so "unit"
        // (meta-kernel's own, zero fields) is only findable here if !!import's own entries were
        // genuinely merged into the type-name namespace, not just validated as well-formed URIs.
        SchemaResolver resolver = new SchemaResolver(loadMetaKernelAndMeta());
        SchemaDocument miniDocument = new SchemaParser(MINI_DOCUMENT_IMPORT_MERGED).parseSchemaDocument();

        TsonSchema resolved = resolver.resolveAll(miniDocument);

        // Transitive, per SchemaResolver's own induction: direct supertype + its own supertype chain.
        assertEquals(List.of("unit", "atom", "top"), resolved.entries().get("my_type").supertypes());
        // Imported entries are visible during resolution but never part of the result itself.
        assertEquals(Set.of("my_type"), resolved.entries().keySet());
    }

    private static final String MINI_DOCUMENT_IMPORT_COLLIDES_WITH_LOCAL = """
            !!id:"https://example.test/mini.tn1"
            !!meta:"https://tson.io/2026/32/m/meta.tn1"
            !!import:"https://tson.io/2026/32/m/meta-kernel.tn1"
            {
              void => !unit {}
            }
            """;

    @Test
    void resolveAllRejectsALocalDeclarationCollidingWithAnImportedName() {
        // meta-kernel itself already declares "void" -- redeclaring it locally while also importing
        // meta-kernel is exactly SchemaValidator's own "collides with an entry of the same name
        // brought in by !!import" rule, now caught here too, one stage earlier.
        SchemaResolver resolver = new SchemaResolver(loadMetaKernelAndMeta());
        SchemaDocument miniDocument = new SchemaParser(MINI_DOCUMENT_IMPORT_COLLIDES_WITH_LOCAL).parseSchemaDocument();

        SchemaValidationException thrown = assertThrows(
                SchemaValidationException.class, () -> resolver.resolveAll(miniDocument));
        assertTrue(thrown.getMessage().contains("void"));
        assertTrue(thrown.getMessage().contains("!!import"));
    }

    private static final String MINI_DOCUMENT_TWO_IMPORTS_COLLIDE = """
            !!id:"https://example.test/mini.tn1"
            !!meta:"https://tson.io/2026/32/m/meta.tn1"
            !!import:"https://tson.io/2026/32/m/meta-kernel.tn1"
            !!import:"https://tson.io/2026/32/m/meta.tn1"
            {
              placeholder => unit
            }
            """;

    @Test
    void resolveAllRejectsTheSameNameDeclaredByMoreThanOneImport() {
        // meta.tn1's own registered entries already carry meta-kernel's whole namespace merged in
        // (via meta.tn1's own real !!import) -- so importing both here means "unit" (among many
        // others) is declared by both imports, the "more than one !!import" case specifically.
        SchemaResolver resolver = new SchemaResolver(loadMetaKernelAndMeta());
        SchemaDocument miniDocument = new SchemaParser(MINI_DOCUMENT_TWO_IMPORTS_COLLIDE).parseSchemaDocument();

        SchemaValidationException thrown = assertThrows(
                SchemaValidationException.class, () -> resolver.resolveAll(miniDocument));
        assertTrue(thrown.getMessage().contains("more than one !!import"));
    }

    // ── DefaultSchemaCoordinator's own bootstrap behavior ──

    @Test
    void coordinatorBootstrapsMetaKernelFromAnEmptyRegistryWithNoInfiniteLoop() {
        TsonCompiledRegistry registry = new TsonCompiledRegistry(ParserFactoryRegistry.dom());
        DefaultSchemaCoordinator coordinator = new DefaultSchemaCoordinator(registry);

        // Meta-kernel's own !!meta names itself -- if resolve() ever fell through to the generic
        // fetch-and-resolve-via-SchemaResolver(this) path for this URI, this call would recurse
        // forever (resolveAll -> compiledMetaSchema -> coordinator.resolve(sameUri) -> ...).
        // Completing at all is the proof; the assertions below just confirm it's genuinely usable.
        TsonSchemaParser compiled = coordinator.resolve(DefaultSchemaCoordinator.META_KERNEL_ID);

        // 58, matching a genuinely registered meta-kernel: the one-off bootstrap runs
        // MetaKernelParser's own raw output through a fresh, throwaway SchemaRegistry (discarded
        // immediately after, never the shared registry this coordinator wraps) purely so
        // SchemaValidator's own materialization step -- which synthesizes 9 extra entries for
        // argument-bearing type-refs, e.g. enum's own "members: set<token>" -- runs before
        // compiling. Never cached (see the next test) -- only the *quality* of the one-off result
        // changed, not its lifetime.
        assertEquals(58, compiled.schema().entries().size());
        assertEquals(java.util.Map.of(), compiled.get("top").read(new Parser("{}").parseDocument().root()));
    }

    @Test
    void coordinatorNeverCachesTheBootstrapResultAndReBootstrapsEachTime() {
        // On the user's own explicit direction: the one-off bootstrap must never be registered or
        // cached here -- the "real", permanent, materialized registry entry for meta-kernel is
        // meant to come from a separate, deliberate "load and register" step done once elsewhere.
        TsonCompiledRegistry registry = new TsonCompiledRegistry(ParserFactoryRegistry.dom());
        DefaultSchemaCoordinator coordinator = new DefaultSchemaCoordinator(registry);

        TsonSchemaParser first = coordinator.resolve(DefaultSchemaCoordinator.META_KERNEL_ID);
        TsonSchemaParser second = coordinator.resolve(DefaultSchemaCoordinator.META_KERNEL_ID);

        assertNotSame(first, second);
        assertTrue(registry.get(DefaultSchemaCoordinator.META_KERNEL_ID).isEmpty());
        assertTrue(registry.schemaRegistry().get(DefaultSchemaCoordinator.META_KERNEL_ID).isEmpty());
    }

    @Test
    void coordinatorWithTheDefaultSourceThrowsClearlyForAnUnregisteredNonBootstrapUri() {
        TsonCompiledRegistry registry = new TsonCompiledRegistry(ParserFactoryRegistry.dom());
        DefaultSchemaCoordinator coordinator = new DefaultSchemaCoordinator(registry);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> coordinator.resolve("https://tson.io/2026/32/m/meta.tn1"));
        assertTrue(thrown.getMessage().contains("no fetch capability"));
    }

    @Test
    void coordinatorResolvesANonBootstrapUriGenericallyViaAPluggedInSchemaSource() {
        // Proves the generic fetch -> parse -> resolve -> register -> compile path works end to end
        // for a real, non-trivial schema (meta.tn1 itself), not just the meta-kernel special case --
        // BundledSchemaSource hands back meta.tn1's own real bundled source text, and resolution
        // proceeds through SchemaResolver(this coordinator).
        //
        // Meta-kernel itself must already be explicitly, permanently registered first: meta.tn1's
        // own !!import of meta-kernel is merged twice over, by two different mechanisms --
        // SchemaResolver's own resolution-time merge (which goes through this coordinator, so the
        // one-off bootstrap alone would satisfy it) *and* SchemaValidator's own registration-time
        // merge (run inside SchemaRegistry#register, via its own registered-only SchemaLoader,
        // which knows nothing about this coordinator or its bootstrap case). The one-off bootstrap
        // is never registered into SchemaRegistry (see the "never caches" test above), so without
        // this explicit step, registering meta.tn1 would fail validation with "!!import '...' is
        // not registered" even though resolution itself succeeded.
        MetaSchema metaKernelForBinder = MetaKernelParser.parse();
        TsonSchema materializedMetaKernel = new SchemaRegistry().register(metaKernelForBinder);
        ParserFactoryRegistry objectFactories =
                ParserFactoryRegistry.object(materializedMetaKernel, TsonAtomContext.defaultContext());
        TsonCompiledRegistry registry = new TsonCompiledRegistry(objectFactories);
        registry.register(MetaKernelParser.parse());
        DefaultSchemaCoordinator coordinator = new DefaultSchemaCoordinator(registry, BundledSchemaSource.INSTANCE);

        TsonSchemaParser compiled = coordinator.resolve(BundledSchemaSource.META_TN1_ID);

        assertEquals("BASE64", compiled.get("binary_encoding").read(new Parser("BASE64").parseDocument().root()));
        // Still there, from the explicit pre-registration step above -- meta.tn1's own resolution
        // didn't need to (and doesn't) re-register it.
        assertTrue(registry.get(DefaultSchemaCoordinator.META_KERNEL_ID).isPresent());
    }
}
