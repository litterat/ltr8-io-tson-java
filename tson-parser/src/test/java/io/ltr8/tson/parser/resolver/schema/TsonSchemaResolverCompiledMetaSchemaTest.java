package io.ltr8.tson.parser.resolver.schema;

import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.parser.TsonDataParser;
import io.ltr8.tson.parser.TsonSchemaParser;
import io.ltr8.tson.parser.ast.schema.SchemaDocument;
import io.ltr8.tson.parser.bind.TsonObjectBinding;
import io.ltr8.tson.parser.resolver.TsonAtomContext;
import io.ltr8.tson.parser.resolver.schema.compiled.TsonParserFactoryRegistry;
import io.ltr8.tson.parser.resolver.schema.compiled.TsonCompiledRegistry;
import io.ltr8.tson.parser.resolver.schema.compiled.TsonCompiledSchema;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.TsonSchemaLinker;
import io.ltr8.tson.schema.TsonSchemaRegistry;
import io.ltr8.tson.schema.TsonSchemaValidationException;
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
 * Proves the guarantee {@code TsonSchemaResolver}'s own private {@code compiledMetaSchema} relies on
 * -- a {@link TsonCompiledSchemaLoader} that already has meta-kernel and meta.tn1 registered and
 * compiled can, given a document's own real {@code !!meta} target (just {@code
 * document.meta()}, a URI), {@link TsonCompiledSchemaLoader#load} it and get back a *compiled*
 * reader genuinely usable to read real data -- not merely present. Exercised directly against the
 * loader itself, not through the resolver, since {@code compiledMetaSchema} is a private
 * pass-through with no logic of its own beyond that one call. Proven in both directions of the real
 * governing chain: core.tn1's own {@code !!meta} target (meta.tn1) and meta.tn1's own {@code !!meta}
 * target (meta-kernel itself). Also covers {@link TsonSchemaResolver#resolveSchema(SchemaDocument)}
 * itself, separately, below. Deliberately doesn't touch {@code bindAtomInstance} at all (see that
 * method's own Javadoc for why that's a separate, later step); this only proves the wiring to
 * *reach* a compiled governing schema works.
 */
class TsonSchemaResolverCompiledMetaSchemaTest {

    /**
     * Now that {@code bindAtomInstance} needs a real, object-binding-mode compiled reader for the
     * schema it's resolving against (see {@code TsonSchemaResolver}'s own field), meta.tn1 itself can no
     * longer be loaded via a bare DOM-mode registry -- its own Instance declarations (e.g. {@code
     * binary_encoding => !enum [...]}) go through {@code resolveInstance}/{@code bindAtomInstance}
     * just like any other schema's. Materialize meta-kernel through a throwaway {@code
     * TsonSchemaRegistry} first (object mode's own {@code TsonParserFactoryRegistry} needs a materialized
     * schema to validate against up front), build the object-mode registry from it, pre-register
     * meta-kernel so the loader's own bootstrap special-case is never reached, then resolve
     * meta.tn1 itself via {@link BundledSchemaSource} -- {@link DefaultTsonCompiledSchemaLoader#load}'s
     * own generic fetch-parse-resolve-register-compile path, not a hand-rolled duplicate of it (the
     * now-deleted {@code MetaTn1Parser} was exactly that duplicate; this is what replaced it).
     *
     * <p><b>Meta-kernel itself is pre-registered via ordinary {@code TsonSchemaResolver.resolveSchema}, not
     * the raw bootstrap output</b> (2026-07-26, {@code TsonSchemaRegistry#register} now refuses <i>any</i>
     * self-referential schema with {@code bootstrap() == true}, materialized or not -- see that
     * method's own Javadoc). {@code linkBootstrap(...)} still runs once, purely to get a
     * genuinely materialized shape to build {@code TsonObjectBinding.factoryRegistry(...)} against -- that
     * value itself is never registered. The loader built from it then resolves meta-kernel's own
     * document the ordinary way (its own bootstrap branch supplies the structure namespace, so even
     * {@code boolean => !enum [...]} resolves correctly despite the forward reference); that result
     * carries no {@code bootstrap} flag, so {@link TsonSchemaRegistry#register} accepts it. Mirrors
     * {@code MetaTn1CompiledEndToEndTest#registerMeta}'s own pattern.
     */
    private static DefaultTsonCompiledSchemaLoader loadMetaKernelAndMeta() {
        TsonSchema metaKernelBootstrap = MetaKernelBootstrapResolver.getMetaKernelSchema();
        TsonLinkedSchema materializedMetaKernelBootstrap = TsonSchemaLinker.linkBootstrap(metaKernelBootstrap);
        DataBindContext context = TsonAtomContext.defaultContext();
        TsonParserFactoryRegistry objectFactories = TsonObjectBinding.factoryRegistry(materializedMetaKernelBootstrap.schema(), context);

        TsonCompiledRegistry registry = new TsonCompiledRegistry(objectFactories);
        DefaultTsonCompiledSchemaLoader loader = new DefaultTsonCompiledSchemaLoader(registry, BundledSchemaSource.INSTANCE);

        String metaKernelSource = BundledSchemaSource.INSTANCE.fetch(BundledSchemaSource.META_KERNEL_ID);
        SchemaDocument metaKernelDocument = new TsonSchemaParser(metaKernelSource).parseSchemaDocument();
        TsonSchema metaKernel = new TsonSchemaResolver(loader).resolveSchema(metaKernelDocument);
        registry.register(metaKernel);

        loader.load(BundledSchemaSource.META_TN1_ID);

        return loader;
    }

    /**
     * A standalone way to get a plain, registrable, non-bootstrap meta-kernel {@link TsonSchema}
     * value -- resolved via its own throwaway object-mode loader (the only mode {@code
     * bindAtomInstance}'s own {@code (Top) metaParser.get(...).read(...)} cast can work against, for
     * meta-kernel's own {@code Instance} declarations), independent of whatever {@link
     * TsonParserFactoryRegistry} mode the *caller's* own registry happens to use. Used by tests that
     * need meta-kernel registered into a DOM-mode registry for some *other* scenario they're testing
     * (e.g. "meta.tn1 was never registered"), where object mode would be beside the point.
     */
    private static TsonSchema resolveMetaKernelOrdinarily() {
        TsonSchema metaKernelBootstrap = MetaKernelBootstrapResolver.getMetaKernelSchema();
        TsonLinkedSchema materializedMetaKernelBootstrap = TsonSchemaLinker.linkBootstrap(metaKernelBootstrap);
        DataBindContext context = TsonAtomContext.defaultContext();
        TsonParserFactoryRegistry objectFactories = TsonObjectBinding.factoryRegistry(materializedMetaKernelBootstrap.schema(), context);
        TsonCompiledRegistry throwawayRegistry = new TsonCompiledRegistry(objectFactories);
        DefaultTsonCompiledSchemaLoader throwawayLoader =
                new DefaultTsonCompiledSchemaLoader(throwawayRegistry, BundledSchemaSource.INSTANCE);

        String metaKernelSource = BundledSchemaSource.INSTANCE.fetch(BundledSchemaSource.META_KERNEL_ID);
        SchemaDocument metaKernelDocument = new TsonSchemaParser(metaKernelSource).parseSchemaDocument();
        return new TsonSchemaResolver(throwawayLoader).resolveSchema(metaKernelDocument);
    }

    @Test
    void coreTn1sOwnMetaTargetResolvesToMetaTn1sCompiledReader() {
        DefaultTsonCompiledSchemaLoader loader = loadMetaKernelAndMeta();
        SchemaDocument coreDocument = new TsonSchemaParser(BundledSchemaSource.INSTANCE.fetch(BundledSchemaSource.CORE_TN1_ID)).parseSchemaDocument();

        assertEquals("https://tson.io/2026/32/m/meta.tn1", coreDocument.meta());

        TsonCompiledSchema compiledMeta = loader.load(coreDocument.meta());

        assertTrue(compiledMeta.schema().entries().containsKey("binary_encoding"));
    }

    @Test
    void theCompiledMetaSchemaGenuinelyReadsRealData() {
        DefaultTsonCompiledSchemaLoader loader = loadMetaKernelAndMeta();
        SchemaDocument coreDocument = new TsonSchemaParser(BundledSchemaSource.INSTANCE.fetch(BundledSchemaSource.CORE_TN1_ID)).parseSchemaDocument();

        TsonCompiledSchema compiledMeta = loader.load(coreDocument.meta());
        Object result = compiledMeta.get("binary_encoding")
                .read(new TsonDataParser("BASE64").parseDocument().root());

        assertEquals("BASE64", result);
    }

    /**
     * The other direction of the same governing chain: meta.tn1's own {@code !!meta} names
     * meta-kernel itself, not meta.tn1's own compiled reader.
     */
    @Test
    void metaTn1sOwnMetaTargetResolvesToMetaKernelsCompiledReader() {
        DefaultTsonCompiledSchemaLoader loader = loadMetaKernelAndMeta();
        SchemaDocument metaDocument =
                new TsonSchemaParser(BundledSchemaSource.INSTANCE.fetch(BundledSchemaSource.META_TN1_ID)).parseSchemaDocument();

        assertEquals(BundledSchemaSource.META_KERNEL_ID, metaDocument.meta());

        TsonCompiledSchema compiledMetaKernel = loader.load(metaDocument.meta());

        // "integer_type" is meta-kernel's own -- not one of meta.tn1's own 31 declarations -- so its
        // presence confirms this genuinely reached meta-kernel's compiled reader, not meta.tn1's own.
        assertTrue(compiledMetaKernel.schema().entries().containsKey("integer_type"));
    }

    @Test
    void theCompiledMetaKernelSchemaGenuinelyReadsRealData() {
        DefaultTsonCompiledSchemaLoader loader = loadMetaKernelAndMeta();
        SchemaDocument metaDocument =
                new TsonSchemaParser(BundledSchemaSource.INSTANCE.fetch(BundledSchemaSource.META_TN1_ID)).parseSchemaDocument();

        TsonCompiledSchema compiledMetaKernel = loader.load(metaDocument.meta());
        Object result = compiledMetaKernel.get("product_access_type")
                .read(new TsonDataParser("INDEX").parseDocument().root());

        assertEquals("INDEX", result);
    }

    /**
     * A {@link TsonCompiledSchemaLoader} is required now, not optional (2026-07-27, on the user's own
     * explicit direction, alongside splitting {@code DefinitionResolver} out of this class) -- the
     * old "no loader at all" state this test used to construct via a bare no-arg constructor no
     * longer exists; the constructor rejects a {@code null} loader outright instead.
     */
    @Test
    void constructingWithoutALoaderThrowsClearly() {
        assertThrows(NullPointerException.class, () -> new TsonSchemaResolver(null));
    }

    @Test
    void aLoaderThatNeverGotMetaTn1RegisteredThrowsClearly() {
        TsonCompiledRegistry registry = new TsonCompiledRegistry(TsonParserFactoryRegistry.dom());
        registry.register(resolveMetaKernelOrdinarily()); // meta-kernel only -- no meta.tn1
        DefaultTsonCompiledSchemaLoader loader = new DefaultTsonCompiledSchemaLoader(registry);
        SchemaDocument coreDocument = new TsonSchemaParser(BundledSchemaSource.INSTANCE.fetch(BundledSchemaSource.CORE_TN1_ID)).parseSchemaDocument();

        // meta.tn1 isn't meta-kernel's own well-known bootstrap case, and the default TsonSchemaSource
        // fetches nothing -- so this is exactly TsonSchemaSource.registeredOnly()'s own rejection.
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> loader.load(coreDocument.meta()));
        assertTrue(thrown.getMessage().contains("meta.tn1"));
        assertTrue(thrown.getMessage().contains("no fetch capability"));
    }

    // ── resolveSchema(SchemaDocument)'s own validate-then-derive behavior ──

    private static final String MINI_DOCUMENT = """
            !!id:"https://example.test/mini.tn1"
            !!meta:"https://tson.io/2026/32/m/meta.tn1"
            {
              void => !unit {}
            }
            """;

    @Test
    void resolveSchemaDerivesStructureNamespaceFromTheLoaderAutomatically() {
        TsonSchemaResolver resolver = new TsonSchemaResolver(loadMetaKernelAndMeta());
        SchemaDocument miniDocument = new TsonSchemaParser(MINI_DOCUMENT).parseSchemaDocument();

        // "unit" is neither local to mini.tn1 nor imported by it -- only reachable if resolveSchema
        // itself derived the structure namespace from the loader's own meta.tn1 entry (which in
        // turn carries meta-kernel's own entries, merged in via meta.tn1's real !!import).
        TsonSchema resolved = resolver.resolveSchema(miniDocument);

        TypeDefinition voidDef = resolved.entries().get("void");
        assertEquals(new Unit(), voidDef.body());
    }

    @Test
    void resolveSchemaThrowsClearlyWhenTheMetaTargetCantBeResolvedAtAll() {
        TsonCompiledRegistry registry = new TsonCompiledRegistry(TsonParserFactoryRegistry.dom());
        TsonSchemaResolver resolver = new TsonSchemaResolver(new DefaultTsonCompiledSchemaLoader(registry));
        SchemaDocument miniDocument = new TsonSchemaParser(MINI_DOCUMENT).parseSchemaDocument();

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> resolver.resolveSchema(miniDocument));
        assertTrue(thrown.getMessage().contains("meta.tn1"));
    }

    private static final String MINI_DOCUMENT_NO_ID = """
            !!meta:"https://tson.io/2026/32/m/meta.tn1"
            {
              void => !unit {}
            }
            """;

    @Test
    void resolveSchemaThrowsClearlyWhenIdIsAbsent() {
        TsonSchemaResolver resolver = new TsonSchemaResolver(loadMetaKernelAndMeta());
        SchemaDocument noIdDocument = new TsonSchemaParser(MINI_DOCUMENT_NO_ID).parseSchemaDocument();

        assertTrue(noIdDocument.id().isEmpty());
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> resolver.resolveSchema(noIdDocument));
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
    void resolveSchemaThrowsClearlyWhenIdIsNotAValidCanonicalIdentity() {
        TsonSchemaResolver resolver = new TsonSchemaResolver(loadMetaKernelAndMeta());
        SchemaDocument malformedIdDocument = new TsonSchemaParser(MINI_DOCUMENT_MALFORMED_ID).parseSchemaDocument();

        // "mini.tn1" alone is a syntactically valid relative-reference URI, but has no scheme --
        // CanonicalIdentity.of's own rejection, surfaced here via TsonSchemaRegistry.validateIdentity.
        assertThrows(TsonSchemaValidationException.class, () -> resolver.resolveSchema(malformedIdDocument));
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
    void resolveSchemaThrowsClearlyWhenAnImportUriIsNotAValidCanonicalIdentity() {
        TsonSchemaResolver resolver = new TsonSchemaResolver(loadMetaKernelAndMeta());
        SchemaDocument malformedImportDocument = new TsonSchemaParser(MINI_DOCUMENT_MALFORMED_IMPORT).parseSchemaDocument();

        assertEquals(1, malformedImportDocument.imports().size());
        // "meta-kernel.tn1" alone is a syntactically valid relative-reference URI, but has no scheme.
        assertThrows(TsonSchemaValidationException.class, () -> resolver.resolveSchema(malformedImportDocument));
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
    void resolveSchemaGenuinelyMergesImportedEntriesIntoTheTypeNameNamespace() {
        // A bare type-ref (§8.3) is carried through unverified regardless of whether the target
        // exists anywhere, so that alone wouldn't prove anything -- composition is the real test:
        // resolveComposition does exactly one resolved.get(supertypeName), no fallback, so "unit"
        // (meta-kernel's own, zero fields) is only findable here if !!import's own entries were
        // genuinely merged into the type-name namespace, not just validated as well-formed URIs.
        TsonSchemaResolver resolver = new TsonSchemaResolver(loadMetaKernelAndMeta());
        SchemaDocument miniDocument = new TsonSchemaParser(MINI_DOCUMENT_IMPORT_MERGED).parseSchemaDocument();

        TsonSchema resolved = resolver.resolveSchema(miniDocument);

        // Transitive, per TsonSchemaResolver's own induction: direct supertype + its own supertype chain.
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
    void resolveSchemaRejectsALocalDeclarationCollidingWithAnImportedName() {
        // meta-kernel itself already declares "void" -- redeclaring it locally while also importing
        // meta-kernel is exactly SchemaValidator's own "collides with an entry of the same name
        // brought in by !!import" rule, now caught here too, one stage earlier.
        TsonSchemaResolver resolver = new TsonSchemaResolver(loadMetaKernelAndMeta());
        SchemaDocument miniDocument = new TsonSchemaParser(MINI_DOCUMENT_IMPORT_COLLIDES_WITH_LOCAL).parseSchemaDocument();

        TsonSchemaValidationException thrown = assertThrows(
                TsonSchemaValidationException.class, () -> resolver.resolveSchema(miniDocument));
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
    void resolveSchemaRejectsTheSameNameDeclaredByMoreThanOneImport() {
        // meta.tn1's own registered entries already carry meta-kernel's whole namespace merged in
        // (via meta.tn1's own real !!import) -- so importing both here means "unit" (among many
        // others) is declared by both imports, the "more than one !!import" case specifically.
        TsonSchemaResolver resolver = new TsonSchemaResolver(loadMetaKernelAndMeta());
        SchemaDocument miniDocument = new TsonSchemaParser(MINI_DOCUMENT_TWO_IMPORTS_COLLIDE).parseSchemaDocument();

        TsonSchemaValidationException thrown = assertThrows(
                TsonSchemaValidationException.class, () -> resolver.resolveSchema(miniDocument));
        assertTrue(thrown.getMessage().contains("more than one !!import"));
    }

    // ── DefaultTsonCompiledSchemaLoader's own bootstrap behavior ──

    @Test
    void loaderBootstrapsMetaKernelFromAnEmptyRegistryWithNoInfiniteLoop() {
        TsonCompiledRegistry registry = new TsonCompiledRegistry(TsonParserFactoryRegistry.dom());
        DefaultTsonCompiledSchemaLoader loader = new DefaultTsonCompiledSchemaLoader(registry);

        // Meta-kernel's own !!meta names itself -- if resolve() ever fell through to the generic
        // fetch-and-resolve-via-TsonSchemaResolver(this) path for this URI, this call would recurse
        // forever (resolveSchema -> compiledMetaSchema -> loader.load(sameUri) -> ...).
        // Completing at all is the proof; the assertions below just confirm it's genuinely usable.
        TsonCompiledSchema compiled = loader.load(BundledSchemaSource.META_KERNEL_ID);

        // 58, matching a genuinely registered meta-kernel: the one-off bootstrap runs
        // MetaKernelBootstrapResolver's own raw output through TsonSchemaLinker.linkBootstrap (no
        // registry involved at all) purely so TsonSchemaLinker's own materialization step -- which
        // synthesizes 9 extra entries for argument-bearing type-refs, e.g. enum's own "members:
        // set<token>" -- runs before compiling. Never cached (see the next test) -- only the
        // *quality* of the one-off result changed, not its lifetime.
        assertEquals(58, compiled.schema().entries().size());
        assertEquals(java.util.Map.of(), compiled.get("top").read(new TsonDataParser("{}").parseDocument().root()));
    }

    @Test
    void loaderNeverCachesTheBootstrapResultAndReBootstrapsEachTime() {
        // On the user's own explicit direction: the one-off bootstrap must never be registered or
        // cached here -- the "real", permanent, materialized registry entry for meta-kernel is
        // meant to come from a separate, deliberate "load and register" step done once elsewhere.
        TsonCompiledRegistry registry = new TsonCompiledRegistry(TsonParserFactoryRegistry.dom());
        DefaultTsonCompiledSchemaLoader loader = new DefaultTsonCompiledSchemaLoader(registry);

        TsonCompiledSchema first = loader.load(BundledSchemaSource.META_KERNEL_ID);
        TsonCompiledSchema second = loader.load(BundledSchemaSource.META_KERNEL_ID);

        assertNotSame(first, second);
        assertTrue(registry.get(BundledSchemaSource.META_KERNEL_ID).isEmpty());
        assertTrue(registry.schemaRegistry().get(BundledSchemaSource.META_KERNEL_ID).isEmpty());
    }

    @Test
    void loaderWithTheDefaultSourceThrowsClearlyForAnUnregisteredNonBootstrapUri() {
        TsonCompiledRegistry registry = new TsonCompiledRegistry(TsonParserFactoryRegistry.dom());
        DefaultTsonCompiledSchemaLoader loader = new DefaultTsonCompiledSchemaLoader(registry);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> loader.load("https://tson.io/2026/32/m/meta.tn1"));
        assertTrue(thrown.getMessage().contains("no fetch capability"));
    }

    @Test
    void loaderResolvesANonBootstrapUriGenericallyViaAPluggedInSchemaSource() {
        // Proves the generic fetch -> parse -> resolve -> register -> compile path works end to end
        // for a real, non-trivial schema (meta.tn1 itself), not just the meta-kernel special case --
        // BundledSchemaSource hands back meta.tn1's own real bundled source text, and resolution
        // proceeds through TsonSchemaResolver(this loader).
        //
        // Meta-kernel itself must already be explicitly, permanently registered first: meta.tn1's
        // own !!import of meta-kernel is merged twice over, by two different mechanisms --
        // TsonSchemaResolver's own resolution-time merge (which goes through this loader, so the
        // one-off bootstrap alone would satisfy it) *and* SchemaValidator's own registration-time
        // merge (run inside TsonSchemaRegistry#register, via its own registered-only TsonSchemaLoader,
        // which knows nothing about this loader or its bootstrap case). The one-off bootstrap
        // is never registered into TsonSchemaRegistry (see the "never caches" test above), so without
        // this explicit step, registering meta.tn1 would fail validation with "!!import '...' is
        // not registered" even though resolution itself succeeded.
        TsonSchema metaKernelBootstrap = MetaKernelBootstrapResolver.getMetaKernelSchema();
        TsonLinkedSchema materializedMetaKernelBootstrap = TsonSchemaLinker.linkBootstrap(metaKernelBootstrap);
        TsonParserFactoryRegistry objectFactories =
                TsonObjectBinding.factoryRegistry(materializedMetaKernelBootstrap.schema(), TsonAtomContext.defaultContext());
        TsonCompiledRegistry registry = new TsonCompiledRegistry(objectFactories);
        DefaultTsonCompiledSchemaLoader loader = new DefaultTsonCompiledSchemaLoader(registry, BundledSchemaSource.INSTANCE);

        // Registered via ordinary TsonSchemaResolver.resolveSchema, not the raw bootstrap output --
        // TsonSchemaRegistry#register now refuses any self-referential schema with bootstrap() == true,
        // materialized or not (see its own Javadoc); resolveSchema never sets that flag.
        String metaKernelSource = BundledSchemaSource.INSTANCE.fetch(BundledSchemaSource.META_KERNEL_ID);
        SchemaDocument metaKernelDocument = new TsonSchemaParser(metaKernelSource).parseSchemaDocument();
        TsonSchema metaKernel = new TsonSchemaResolver(loader).resolveSchema(metaKernelDocument);
        registry.register(metaKernel);

        TsonCompiledSchema compiled = loader.load(BundledSchemaSource.META_TN1_ID);

        assertEquals("BASE64", compiled.get("binary_encoding").read(new TsonDataParser("BASE64").parseDocument().root()));
        // Still there, from the explicit pre-registration step above -- meta.tn1's own resolution
        // didn't need to (and doesn't) re-register it.
        assertTrue(registry.get(BundledSchemaSource.META_KERNEL_ID).isPresent());
    }
}
