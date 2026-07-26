package io.ltr8.tson.parser.resolver.schema;

import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.parser.Parser;
import io.ltr8.tson.parser.SchemaParser;
import io.ltr8.tson.parser.ast.schema.SchemaDocument;
import io.ltr8.tson.parser.resolver.TsonAtomContext;
import io.ltr8.tson.parser.resolver.schema.compiled.ParserFactoryRegistry;
import io.ltr8.tson.parser.resolver.schema.compiled.TsonCompiledRegistry;
import io.ltr8.tson.parser.resolver.schema.compiled.TsonSchemaParser;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.SchemaRegistry;
import io.ltr8.tson.schema.SchemaValidationException;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.Unit;
import org.junit.jupiter.api.Test;

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
 * registered and compiled, can look up a document's own real {@code !!meta} target and get back its
 * *compiled* reader, genuinely usable to read real data -- not merely present. Proven in both
 * directions of the real governing chain: core.tn1's own {@code !!meta} target (meta.tn1) and
 * meta.tn1's own {@code !!meta} target (meta-kernel itself). Deliberately doesn't touch {@code
 * bindAtomInstance} at all (see that method's own Javadoc for why that's a separate, later step);
 * this only proves the wiring to *reach* a compiled governing schema works.
 */
class SchemaResolverCompiledMetaSchemaTest {

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
     *
     * <p><b>Meta-kernel itself is pre-registered via ordinary {@code SchemaResolver.resolveAll}, not
     * the raw bootstrap output</b> (2026-07-26, {@code SchemaRegistry#register} now refuses <i>any</i>
     * self-referential schema with {@code bootstrap() == true}, materialized or not -- see that
     * method's own Javadoc). {@code materializeBootstrap(...)} still runs once, purely to get a
     * genuinely materialized shape to build {@code ParserFactoryRegistry.object(...)} against -- that
     * value itself is never registered. The coordinator built from it then resolves meta-kernel's own
     * document the ordinary way (its own bootstrap branch supplies the structure namespace, so even
     * {@code boolean => !enum [...]} resolves correctly despite the forward reference); that result
     * carries no {@code bootstrap} flag, so {@link SchemaRegistry#register} accepts it. Mirrors
     * {@code MetaTn1CompiledEndToEndTest#registerMeta}'s own pattern.
     */
    private static DefaultSchemaCoordinator loadMetaKernelAndMeta() {
        TsonSchema metaKernelBootstrap = MetaKernelParser.getMetaKernelSchema();
        TsonSchema materializedMetaKernelBootstrap = new SchemaRegistry().materializeBootstrap(metaKernelBootstrap);
        DataBindContext context = TsonAtomContext.defaultContext();
        ParserFactoryRegistry objectFactories = ParserFactoryRegistry.object(materializedMetaKernelBootstrap, context);

        TsonCompiledRegistry registry = new TsonCompiledRegistry(objectFactories);
        DefaultSchemaCoordinator coordinator = new DefaultSchemaCoordinator(registry, BundledSchemaSource.INSTANCE);

        String metaKernelSource = BundledSchemaSource.INSTANCE.fetch(BundledSchemaSource.META_KERNEL_ID);
        SchemaDocument metaKernelDocument = new SchemaParser(metaKernelSource).parseSchemaDocument();
        TsonSchema metaKernel = new SchemaResolver(coordinator).resolveAll(metaKernelDocument);
        registry.register(metaKernel);

        coordinator.resolve(BundledSchemaSource.META_TN1_ID);

        return coordinator;
    }

    /**
     * A standalone way to get a plain, registrable, non-bootstrap meta-kernel {@link TsonSchema}
     * value -- resolved via its own throwaway object-mode coordinator (the only mode {@code
     * bindAtomInstance}'s own {@code (Top) metaParser.get(...).read(...)} cast can work against, for
     * meta-kernel's own {@code Instance} declarations), independent of whatever {@link
     * ParserFactoryRegistry} mode the *caller's* own registry happens to use. Used by tests that
     * need meta-kernel registered into a DOM-mode registry for some *other* scenario they're testing
     * (e.g. "meta.tn1 was never registered"), where object mode would be beside the point.
     */
    private static TsonSchema resolveMetaKernelOrdinarily() {
        TsonSchema metaKernelBootstrap = MetaKernelParser.getMetaKernelSchema();
        TsonSchema materializedMetaKernelBootstrap = new SchemaRegistry().materializeBootstrap(metaKernelBootstrap);
        DataBindContext context = TsonAtomContext.defaultContext();
        ParserFactoryRegistry objectFactories = ParserFactoryRegistry.object(materializedMetaKernelBootstrap, context);
        TsonCompiledRegistry throwawayRegistry = new TsonCompiledRegistry(objectFactories);
        DefaultSchemaCoordinator throwawayCoordinator =
                new DefaultSchemaCoordinator(throwawayRegistry, BundledSchemaSource.INSTANCE);

        String metaKernelSource = BundledSchemaSource.INSTANCE.fetch(BundledSchemaSource.META_KERNEL_ID);
        SchemaDocument metaKernelDocument = new SchemaParser(metaKernelSource).parseSchemaDocument();
        return new SchemaResolver(throwawayCoordinator).resolveAll(metaKernelDocument);
    }

    @Test
    void coreTn1sOwnMetaTargetResolvesToMetaTn1sCompiledReader() {
        SchemaResolver resolver = new SchemaResolver(loadMetaKernelAndMeta());
        SchemaDocument coreDocument = new SchemaParser(BundledSchemaSource.INSTANCE.fetch(BundledSchemaSource.CORE_TN1_ID)).parseSchemaDocument();

        assertEquals("https://tson.io/2026/32/m/meta.tn1", coreDocument.meta());

        TsonSchemaParser compiledMeta = resolver.compiledMetaSchema(coreDocument);

        assertTrue(compiledMeta.schema().entries().containsKey("binary_encoding"));
    }

    @Test
    void theCompiledMetaSchemaGenuinelyReadsRealData() {
        SchemaResolver resolver = new SchemaResolver(loadMetaKernelAndMeta());
        SchemaDocument coreDocument = new SchemaParser(BundledSchemaSource.INSTANCE.fetch(BundledSchemaSource.CORE_TN1_ID)).parseSchemaDocument();

        TsonSchemaParser compiledMeta = resolver.compiledMetaSchema(coreDocument);
        Object result = compiledMeta.get("binary_encoding")
                .read(new Parser("BASE64").parseDocument().root());

        assertEquals("BASE64", result);
    }

    /**
     * The other direction of the same governing chain: meta.tn1's own {@code !!meta} names
     * meta-kernel itself, not meta.tn1's own compiled reader -- {@code compiledMetaSchema} had no
     * direct test proving this specific hop until now (only exercised indirectly, as a side effect
     * of {@code loadMetaKernelAndMeta}'s own {@code resolveAll} call resolving meta.tn1's document).
     */
    @Test
    void metaTn1sOwnMetaTargetResolvesToMetaKernelsCompiledReader() {
        SchemaResolver resolver = new SchemaResolver(loadMetaKernelAndMeta());
        SchemaDocument metaDocument =
                new SchemaParser(BundledSchemaSource.INSTANCE.fetch(BundledSchemaSource.META_TN1_ID)).parseSchemaDocument();

        assertEquals(BundledSchemaSource.META_KERNEL_ID, metaDocument.meta());

        TsonSchemaParser compiledMetaKernel = resolver.compiledMetaSchema(metaDocument);

        // "integer_type" is meta-kernel's own -- not one of meta.tn1's own 31 declarations -- so its
        // presence confirms this genuinely reached meta-kernel's compiled reader, not meta.tn1's own.
        assertTrue(compiledMetaKernel.schema().entries().containsKey("integer_type"));
    }

    @Test
    void theCompiledMetaKernelSchemaGenuinelyReadsRealData() {
        SchemaResolver resolver = new SchemaResolver(loadMetaKernelAndMeta());
        SchemaDocument metaDocument =
                new SchemaParser(BundledSchemaSource.INSTANCE.fetch(BundledSchemaSource.META_TN1_ID)).parseSchemaDocument();

        TsonSchemaParser compiledMetaKernel = resolver.compiledMetaSchema(metaDocument);
        Object result = compiledMetaKernel.get("product_access_type")
                .read(new Parser("INDEX").parseDocument().root());

        assertEquals("INDEX", result);
    }

    @Test
    void withNoCoordinatorCompiledMetaSchemaThrowsClearly() {
        SchemaResolver resolver = new SchemaResolver();
        SchemaDocument coreDocument = new SchemaParser(BundledSchemaSource.INSTANCE.fetch(BundledSchemaSource.CORE_TN1_ID)).parseSchemaDocument();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> resolver.compiledMetaSchema(coreDocument));
        assertTrue(thrown.getMessage().contains("SchemaCoordinator"));
    }

    @Test
    void aCoordinatorThatNeverGotMetaTn1RegisteredThrowsClearly() {
        TsonCompiledRegistry registry = new TsonCompiledRegistry(ParserFactoryRegistry.dom());
        registry.register(resolveMetaKernelOrdinarily()); // meta-kernel only -- no meta.tn1
        SchemaResolver resolver = new SchemaResolver(new DefaultSchemaCoordinator(registry));
        SchemaDocument coreDocument = new SchemaParser(BundledSchemaSource.INSTANCE.fetch(BundledSchemaSource.CORE_TN1_ID)).parseSchemaDocument();

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
        TsonSchemaParser compiled = coordinator.resolve(BundledSchemaSource.META_KERNEL_ID);

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

        TsonSchemaParser first = coordinator.resolve(BundledSchemaSource.META_KERNEL_ID);
        TsonSchemaParser second = coordinator.resolve(BundledSchemaSource.META_KERNEL_ID);

        assertNotSame(first, second);
        assertTrue(registry.get(BundledSchemaSource.META_KERNEL_ID).isEmpty());
        assertTrue(registry.schemaRegistry().get(BundledSchemaSource.META_KERNEL_ID).isEmpty());
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
        TsonSchema metaKernelBootstrap = MetaKernelParser.getMetaKernelSchema();
        TsonSchema materializedMetaKernelBootstrap = new SchemaRegistry().materializeBootstrap(metaKernelBootstrap);
        ParserFactoryRegistry objectFactories =
                ParserFactoryRegistry.object(materializedMetaKernelBootstrap, TsonAtomContext.defaultContext());
        TsonCompiledRegistry registry = new TsonCompiledRegistry(objectFactories);
        DefaultSchemaCoordinator coordinator = new DefaultSchemaCoordinator(registry, BundledSchemaSource.INSTANCE);

        // Registered via ordinary SchemaResolver.resolveAll, not the raw bootstrap output --
        // SchemaRegistry#register now refuses any self-referential schema with bootstrap() == true,
        // materialized or not (see its own Javadoc); resolveAll never sets that flag.
        String metaKernelSource = BundledSchemaSource.INSTANCE.fetch(BundledSchemaSource.META_KERNEL_ID);
        SchemaDocument metaKernelDocument = new SchemaParser(metaKernelSource).parseSchemaDocument();
        TsonSchema metaKernel = new SchemaResolver(coordinator).resolveAll(metaKernelDocument);
        registry.register(metaKernel);

        TsonSchemaParser compiled = coordinator.resolve(BundledSchemaSource.META_TN1_ID);

        assertEquals("BASE64", compiled.get("binary_encoding").read(new Parser("BASE64").parseDocument().root()));
        // Still there, from the explicit pre-registration step above -- meta.tn1's own resolution
        // didn't need to (and doesn't) re-register it.
        assertTrue(registry.get(BundledSchemaSource.META_KERNEL_ID).isPresent());
    }
}
