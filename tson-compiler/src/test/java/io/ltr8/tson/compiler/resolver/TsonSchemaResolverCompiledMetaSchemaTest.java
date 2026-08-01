package io.ltr8.tson.compiler.resolver;

import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.compiler.TsonCompiledSchemaLoader;
import io.ltr8.tson.compiler.TsonSchemaParser;
import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.compiler.TsonCompiledMetaSchema;
import io.ltr8.tson.compiler.reader.ValueReaderFactoryRegistry;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.config.TsonCompiledRegistry;
import io.ltr8.tson.schema.TsonBundledSchemas;
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
 * Proves the guarantee {@link SchemaResolver#resolveSchema(SchemaDocument)} relies on to derive
 * its own structure namespace -- a {@link TsonCompiledSchemaLoader} that already has meta-kernel and
 * meta.tn registered and compiled can, given a document's own real {@code !!meta} target (just
 * {@code document.meta()}, a URI), {@link TsonCompiledSchemaLoader#load} it and get back a
 * *compiled* meta-schema genuinely usable to read real data -- not merely present. Exercised
 * directly against the loader itself, not through the resolver, since that's exactly what {@code
 * resolveSchema} does internally with no further logic of its own. Proven in both directions of the
 * real governing chain: core.tn's own {@code !!meta} target (meta.tn) and meta.tn's own {@code
 * !!meta} target (meta-kernel itself). Also covers {@code resolveSchema} itself, separately, below.
 * Deliberately doesn't touch {@code bindAtomInstance} at all (see that
 * method's own Javadoc for why that's a separate, later step); this only proves the wiring to
 * *reach* a compiled governing schema works.
 *
 * <p>Reading an entry that isn't itself a {@code ~}-marked constructor (e.g. {@code
 * binary_encoding}, {@code product_access_type}, {@code top} -- ordinary declarations, not vocabulary
 * this codebase's own factories build against) goes through {@link
 * TsonCompiledMetaSchema#compiledSchema()}'s own unscoped {@code get}, not {@link
 * TsonCompiledMetaSchema#reader}, which is deliberately scoped to constructor-declared entries only
 * (see that method's own Javadoc).
 */
class TsonSchemaResolverCompiledMetaSchemaTest {

    /**
     * Now that {@code bindAtomInstance} needs a real, object-binding-mode compiled reader for the
     * schema it's resolving against (see {@code SchemaResolver}'s own field), meta.tn itself can no
     * longer be loaded via a bare DOM-mode registry -- its own Instance declarations (e.g. {@code
     * binary_encoding => !enum [...]}) go through {@code resolveInstance}/{@code bindAtomInstance}
     * just like any other schema's. Resolve meta-kernel ordinarily first (object mode's own {@link
     * ValueReaderFactoryRegistry#bind} needs no materialized schema up front the way the old
     * eager-validation design did -- see {@code TsonObjectBinder}'s own retirement note), then
     * resolve meta.tn itself via {@link TsonBundledSchemas} -- {@link
     * DefaultTsonCompiledSchemaLoader#load}'s own generic fetch-parse-resolve-register-compile path,
     * not a hand-rolled duplicate of it.
     *
     * <p><b>Meta-kernel itself is registered via ordinary {@code SchemaResolver.resolveSchema}, not
     * the raw bootstrap output</b> ({@code TsonSchemaRegistry#register} refuses <i>any</i>
     * self-referential schema with {@code bootstrap() == true}, materialized or not). The loader's own
     * bootstrap branch supplies the structure namespace while resolving (so even {@code boolean =>
     * !enum [...]} resolves correctly despite the forward reference); that result carries no {@code
     * bootstrap} flag, so {@link TsonSchemaRegistry#register} accepts it. {@code registry.register}'s
     * own {@code governingMeta} argument is meta-kernel's own freshly re-bootstrapped {@link
     * TsonCompiledMetaSchema} (never cached for that identity -- see {@link
     * DefaultTsonCompiledSchemaLoader}'s own Javadoc), since meta-kernel governs itself. Mirrors
     * {@code MetaTn1CompiledEndToEndTest#registerMeta}'s own pattern.
     */
    private static DefaultTsonCompiledSchemaLoader loadMetaKernelAndMeta() {
        DataBindContext context = SchemaMetaNameBinder.defaultContext();
        ValueReaderFactoryRegistry objectFactories = ValueReaderFactoryRegistry.bind(context);

        TsonCompiledRegistry registry = new TsonCompiledRegistry(objectFactories);
        DefaultTsonCompiledSchemaLoader loader = new DefaultTsonCompiledSchemaLoader(registry, TsonBundledSchemas::fetch);

        String metaKernelSource = TsonBundledSchemas.fetch(TsonBundledSchemas.META_KERNEL_ID);
        SchemaDocument metaKernelDocument = new TsonSchemaParser(metaKernelSource).parseSchemaDocument();
        TsonSchema metaKernel = new SchemaResolver(loader).resolveSchema(metaKernelDocument);
        registry.register(metaKernel, loader.load(TsonBundledSchemas.META_KERNEL_ID));

        loader.load(TsonBundledSchemas.META_ID);

        return loader;
    }

    /**
     * A standalone way to get a plain, registrable, non-bootstrap meta-kernel {@link TsonSchema}
     * value -- resolved via its own throwaway object-mode loader (the only mode {@code
     * bindAtomInstance}'s own {@code (Top) metaParser.reader(...).read(...)} cast can work against, for
     * meta-kernel's own {@code Instance} declarations), independent of whatever mode the *caller's*
     * own registry happens to use. Used by tests that need meta-kernel registered into a DOM-mode
     * registry for some *other* scenario they're testing (e.g. "meta.tn was never registered"),
     * where object mode would be beside the point.
     */
    private static TsonSchema resolveMetaKernelOrdinarily() {
        DataBindContext context = SchemaMetaNameBinder.defaultContext();
        ValueReaderFactoryRegistry objectFactories = ValueReaderFactoryRegistry.bind(context);
        TsonCompiledRegistry throwawayRegistry = new TsonCompiledRegistry(objectFactories);
        DefaultTsonCompiledSchemaLoader throwawayLoader =
                new DefaultTsonCompiledSchemaLoader(throwawayRegistry, TsonBundledSchemas::fetch);

        String metaKernelSource = TsonBundledSchemas.fetch(TsonBundledSchemas.META_KERNEL_ID);
        SchemaDocument metaKernelDocument = new TsonSchemaParser(metaKernelSource).parseSchemaDocument();
        return new SchemaResolver(throwawayLoader).resolveSchema(metaKernelDocument);
    }

    @Test
    void coreTn1sOwnMetaTargetResolvesToMetaTn1sCompiledReader() {
        DefaultTsonCompiledSchemaLoader loader = loadMetaKernelAndMeta();
        SchemaDocument coreDocument = new TsonSchemaParser(TsonBundledSchemas.fetch(TsonBundledSchemas.CORE_ID)).parseSchemaDocument();

        // core's own !!meta now names meta.tn with a ?sha256= pin -- compare by identity.
        assertEquals(TsonSchemaRegistry.canonicalIdentity(TsonBundledSchemas.META_ID),
                TsonSchemaRegistry.canonicalIdentity(coreDocument.meta()));

        TsonCompiledMetaSchema compiledMeta = loader.load(coreDocument.meta());

        assertTrue(compiledMeta.schema().entries().containsKey("binary_encoding"));
    }

    @Test
    void theCompiledMetaSchemaGenuinelyReadsRealData() {
        DefaultTsonCompiledSchemaLoader loader = loadMetaKernelAndMeta();
        SchemaDocument coreDocument = new TsonSchemaParser(TsonBundledSchemas.fetch(TsonBundledSchemas.CORE_ID)).parseSchemaDocument();

        TsonCompiledMetaSchema compiledMeta = loader.load(coreDocument.meta());
        Object result = compiledMeta.compiledSchema().get("binary_encoding")
                .read("BASE64");

        assertEquals("BASE64", result);
    }

    /**
     * The other direction of the same governing chain: meta.tn's own {@code !!meta} names
     * meta-kernel itself, not meta.tn's own compiled reader.
     */
    @Test
    void metaTn1sOwnMetaTargetResolvesToMetaKernelsCompiledReader() {
        DefaultTsonCompiledSchemaLoader loader = loadMetaKernelAndMeta();
        SchemaDocument metaDocument =
                new TsonSchemaParser(TsonBundledSchemas.fetch(TsonBundledSchemas.META_ID)).parseSchemaDocument();

        // meta's own !!meta now names meta-kernel with a ?sha256= pin -- compare by identity.
        assertEquals(TsonSchemaRegistry.canonicalIdentity(TsonBundledSchemas.META_KERNEL_ID),
                TsonSchemaRegistry.canonicalIdentity(metaDocument.meta()));

        TsonCompiledMetaSchema compiledMetaKernel = loader.load(metaDocument.meta());

        // "integer_type" is meta-kernel's own -- not one of meta.tn's own 31 declarations -- so its
        // presence confirms this genuinely reached meta-kernel's compiled reader, not meta.tn's own.
        assertTrue(compiledMetaKernel.schema().entries().containsKey("integer_type"));
    }

    @Test
    void theCompiledMetaKernelSchemaGenuinelyReadsRealData() {
        DefaultTsonCompiledSchemaLoader loader = loadMetaKernelAndMeta();
        SchemaDocument metaDocument =
                new TsonSchemaParser(TsonBundledSchemas.fetch(TsonBundledSchemas.META_ID)).parseSchemaDocument();

        TsonCompiledMetaSchema compiledMetaKernel = loader.load(metaDocument.meta());
        Object result = compiledMetaKernel.compiledSchema().get("product_access_type")
                .read("INDEX");

        assertEquals("INDEX", result);
    }

    @Test
    void constructingWithoutALoaderThrowsClearly() {
        assertThrows(NullPointerException.class, () -> new SchemaResolver(null));
    }

    @Test
    void aLoaderThatNeverGotMetaTn1RegisteredThrowsClearly() {
        ValueReaderFactoryRegistry resolver = ValueReaderFactoryRegistry.dom();
        TsonCompiledRegistry registry = new TsonCompiledRegistry(resolver);
        TsonLinkedSchema linkedMetaKernel = TsonSchemaLinker.linkBootstrap(MetaKernelBootstrapResolver.getMetaKernelSchema());
        // meta-kernel only -- no meta.tn -- governed by its own freshly bootstrapped compiled form.
        registry.register(resolveMetaKernelOrdinarily(), TsonCompiledMetaSchema.bootstrap(linkedMetaKernel, resolver));
        DefaultTsonCompiledSchemaLoader loader = new DefaultTsonCompiledSchemaLoader(registry);
        SchemaDocument coreDocument = new TsonSchemaParser(TsonBundledSchemas.fetch(TsonBundledSchemas.CORE_ID)).parseSchemaDocument();

        // meta.tn isn't meta-kernel's own well-known bootstrap case, and the default TsonSchemaSource
        // fetches nothing -- so this is exactly TsonSchemaSource.registeredOnly()'s own rejection.
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> loader.load(coreDocument.meta()));
        assertTrue(thrown.getMessage().contains("meta.tn"));
        assertTrue(thrown.getMessage().contains("no fetch capability"));
    }

    // ── resolveSchema(SchemaDocument)'s own validate-then-derive behavior ──

    private static final String MINI_DOCUMENT = """
            !!id:"https://example.test/mini.tn1"
            !!meta:"https://tson.io/2026/32/m/meta.tn"
            {
              void => !unit {}
            }
            """;

    @Test
    void resolveSchemaDerivesStructureNamespaceFromTheLoaderAutomatically() {
        SchemaResolver resolver = new SchemaResolver(loadMetaKernelAndMeta());
        SchemaDocument miniDocument = new TsonSchemaParser(MINI_DOCUMENT).parseSchemaDocument();

        // "unit" is neither local to mini.tn1 nor imported by it -- only reachable if resolveSchema
        // itself derived the structure namespace from the loader's own meta.tn entry (which in
        // turn carries meta-kernel's own entries, merged in via meta.tn's real !!import).
        TsonSchema resolved = resolver.resolveSchema(miniDocument);

        TypeDefinition voidDef = resolved.entries().get("void");
        assertEquals(new Unit(), voidDef.body());
    }

    @Test
    void resolveSchemaThrowsClearlyWhenTheMetaTargetCantBeResolvedAtAll() {
        TsonCompiledRegistry registry = new TsonCompiledRegistry(ValueReaderFactoryRegistry.dom());
        SchemaResolver resolver = new SchemaResolver(new DefaultTsonCompiledSchemaLoader(registry));
        SchemaDocument miniDocument = new TsonSchemaParser(MINI_DOCUMENT).parseSchemaDocument();

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> resolver.resolveSchema(miniDocument));
        assertTrue(thrown.getMessage().contains("meta.tn"));
    }

    private static final String MINI_DOCUMENT_NO_ID = """
            !!meta:"https://tson.io/2026/32/m/meta.tn"
            {
              void => !unit {}
            }
            """;

    @Test
    void resolveSchemaThrowsClearlyWhenIdIsAbsent() {
        SchemaResolver resolver = new SchemaResolver(loadMetaKernelAndMeta());
        SchemaDocument noIdDocument = new TsonSchemaParser(MINI_DOCUMENT_NO_ID).parseSchemaDocument();

        assertTrue(noIdDocument.id().isEmpty());
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> resolver.resolveSchema(noIdDocument));
        assertTrue(thrown.getMessage().contains("!!id"));
        assertTrue(thrown.getMessage().contains("absent"));
    }

    private static final String MINI_DOCUMENT_MALFORMED_ID = """
            !!id:"mini.tn1"
            !!meta:"https://tson.io/2026/32/m/meta.tn"
            {
              void => !unit {}
            }
            """;

    @Test
    void resolveSchemaThrowsClearlyWhenIdIsNotAValidCanonicalIdentity() {
        SchemaResolver resolver = new SchemaResolver(loadMetaKernelAndMeta());
        SchemaDocument malformedIdDocument = new TsonSchemaParser(MINI_DOCUMENT_MALFORMED_ID).parseSchemaDocument();

        // "mini.tn1" alone is a syntactically valid relative-reference URI, but has no scheme --
        // CanonicalIdentity.of's own rejection, surfaced here via TsonSchemaRegistry.validateIdentity.
        assertThrows(TsonSchemaValidationException.class, () -> resolver.resolveSchema(malformedIdDocument));
    }

    private static final String MINI_DOCUMENT_MALFORMED_IMPORT = """
            !!id:"https://example.test/mini.tn1"
            !!meta:"https://tson.io/2026/32/m/meta.tn"
            !!import:"meta-kernel.tn"
            {
              void => !unit {}
            }
            """;

    @Test
    void resolveSchemaThrowsClearlyWhenAnImportUriIsNotAValidCanonicalIdentity() {
        SchemaResolver resolver = new SchemaResolver(loadMetaKernelAndMeta());
        SchemaDocument malformedImportDocument = new TsonSchemaParser(MINI_DOCUMENT_MALFORMED_IMPORT).parseSchemaDocument();

        assertEquals(1, malformedImportDocument.imports().size());
        // "meta-kernel.tn" alone is a syntactically valid relative-reference URI, but has no scheme.
        assertThrows(TsonSchemaValidationException.class, () -> resolver.resolveSchema(malformedImportDocument));
    }

    private static final String MINI_DOCUMENT_IMPORT_MERGED = """
            !!id:"https://example.test/mini.tn1"
            !!meta:"https://tson.io/2026/32/m/meta.tn"
            !!import:"https://tson.io/2026/32/m/meta-kernel.tn"
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
        SchemaResolver resolver = new SchemaResolver(loadMetaKernelAndMeta());
        SchemaDocument miniDocument = new TsonSchemaParser(MINI_DOCUMENT_IMPORT_MERGED).parseSchemaDocument();

        TsonSchema resolved = resolver.resolveSchema(miniDocument);

        // Transitive, per SchemaResolver's own induction: direct supertype + its own supertype chain.
        assertEquals(List.of("unit", "atom", "top"), resolved.entries().get("my_type").supertypes());
        // Imported entries are visible during resolution but never part of the result itself.
        assertEquals(Set.of("my_type"), resolved.entries().keySet());
    }

    private static final String MINI_DOCUMENT_IMPORT_COLLIDES_WITH_LOCAL = """
            !!id:"https://example.test/mini.tn1"
            !!meta:"https://tson.io/2026/32/m/meta.tn"
            !!import:"https://tson.io/2026/32/m/meta-kernel.tn"
            {
              void => !unit {}
            }
            """;

    @Test
    void resolveSchemaRejectsALocalDeclarationCollidingWithAnImportedName() {
        // meta-kernel itself already declares "void" -- redeclaring it locally while also importing
        // meta-kernel is exactly SchemaValidator's own "collides with an entry of the same name
        // brought in by !!import" rule, now caught here too, one stage earlier.
        SchemaResolver resolver = new SchemaResolver(loadMetaKernelAndMeta());
        SchemaDocument miniDocument = new TsonSchemaParser(MINI_DOCUMENT_IMPORT_COLLIDES_WITH_LOCAL).parseSchemaDocument();

        TsonSchemaValidationException thrown = assertThrows(
                TsonSchemaValidationException.class, () -> resolver.resolveSchema(miniDocument));
        assertTrue(thrown.getMessage().contains("void"));
        assertTrue(thrown.getMessage().contains("!!import"));
    }

    private static final String MINI_DOCUMENT_TWO_IMPORTS_COLLIDE = """
            !!id:"https://example.test/mini.tn1"
            !!meta:"https://tson.io/2026/32/m/meta.tn"
            !!import:"https://tson.io/2026/32/m/meta-kernel.tn"
            !!import:"https://tson.io/2026/32/m/meta.tn"
            {
              placeholder => unit
            }
            """;

    @Test
    void resolveSchemaRejectsTheSameNameDeclaredByMoreThanOneImport() {
        // meta.tn's own registered entries already carry meta-kernel's whole namespace merged in
        // (via meta.tn's own real !!import) -- so importing both here means "unit" (among many
        // others) is declared by both imports, the "more than one !!import" case specifically.
        SchemaResolver resolver = new SchemaResolver(loadMetaKernelAndMeta());
        SchemaDocument miniDocument = new TsonSchemaParser(MINI_DOCUMENT_TWO_IMPORTS_COLLIDE).parseSchemaDocument();

        TsonSchemaValidationException thrown = assertThrows(
                TsonSchemaValidationException.class, () -> resolver.resolveSchema(miniDocument));
        assertTrue(thrown.getMessage().contains("more than one !!import"));
    }

    // ── DefaultTsonCompiledSchemaLoader's own bootstrap behavior ──

    @Test
    void loaderBootstrapsMetaKernelFromAnEmptyRegistryWithNoInfiniteLoop() {
        TsonCompiledRegistry registry = new TsonCompiledRegistry(ValueReaderFactoryRegistry.dom());
        DefaultTsonCompiledSchemaLoader loader = new DefaultTsonCompiledSchemaLoader(registry);

        // Meta-kernel's own !!meta names itself -- if resolve() ever fell through to the generic
        // fetch-and-resolve-via-SchemaResolver(this) path for this URI, this call would recurse
        // forever (resolveSchema -> loader.load(sameUri) -> ...).
        // Completing at all is the proof; the assertions below just confirm it's genuinely usable.
        TsonCompiledMetaSchema compiled = loader.load(TsonBundledSchemas.META_KERNEL_ID);

        // 58, matching a genuinely registered meta-kernel: the one-off bootstrap runs
        // MetaKernelBootstrapResolver's own raw output through TsonSchemaLinker.linkBootstrap (no
        // registry involved at all) purely so TsonSchemaLinker's own materialization step -- which
        // synthesizes 9 extra entries for argument-bearing type-refs, e.g. enum's own "members:
        // set<token>" -- runs before compiling. Never cached (see the next test) -- only the
        // *quality* of the one-off result changed, not its lifetime.
        assertEquals(58, compiled.schema().entries().size());
        assertEquals(java.util.Map.of(),
                compiled.compiledSchema().get("top").read("{}"));
    }

    @Test
    void loaderNeverCachesTheBootstrapResultAndReBootstrapsEachTime() {
        // The one-off bootstrap must never be registered or cached here -- the "real", permanent,
        // materialized registry entry for meta-kernel is meant to come from a separate, deliberate
        // "load and register" step done once elsewhere.
        TsonCompiledRegistry registry = new TsonCompiledRegistry(ValueReaderFactoryRegistry.dom());
        DefaultTsonCompiledSchemaLoader loader = new DefaultTsonCompiledSchemaLoader(registry);

        TsonCompiledMetaSchema first = loader.load(TsonBundledSchemas.META_KERNEL_ID);
        TsonCompiledMetaSchema second = loader.load(TsonBundledSchemas.META_KERNEL_ID);

        assertNotSame(first, second);
        assertTrue(registry.get(TsonBundledSchemas.META_KERNEL_ID).isEmpty());
        assertTrue(registry.schemaRegistry().get(TsonBundledSchemas.META_KERNEL_ID).isEmpty());
    }

    @Test
    void loaderWithTheDefaultSourceThrowsClearlyForAnUnregisteredNonBootstrapUri() {
        TsonCompiledRegistry registry = new TsonCompiledRegistry(ValueReaderFactoryRegistry.dom());
        DefaultTsonCompiledSchemaLoader loader = new DefaultTsonCompiledSchemaLoader(registry);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> loader.load("https://tson.io/2026/32/m/meta.tn"));
        assertTrue(thrown.getMessage().contains("no fetch capability"));
    }

    @Test
    void loaderResolvesANonBootstrapUriGenericallyViaAPluggedInSchemaSource() {
        // Proves the generic fetch -> parse -> resolve -> register -> compile path works end to end
        // for a real, non-trivial schema (meta.tn itself), not just the meta-kernel special case --
        // TsonBundledSchemas hands back meta.tn's own real bundled source text, and resolution
        // proceeds through SchemaResolver(this loader).
        //
        // Meta-kernel itself must already be explicitly, permanently registered first: meta.tn's
        // own !!import of meta-kernel is merged twice over, by two different mechanisms --
        // SchemaResolver's own resolution-time merge (which goes through this loader, so the
        // one-off bootstrap alone would satisfy it) *and* SchemaValidator's own registration-time
        // merge (run inside TsonSchemaRegistry#register, via its own registered-only TsonSchemaLoader,
        // which knows nothing about this loader or its bootstrap case). The one-off bootstrap
        // is never registered into TsonSchemaRegistry (see the "never caches" test above), so without
        // this explicit step, registering meta.tn would fail validation with "!!import '...' is
        // not registered" even though resolution itself succeeded.
        DataBindContext context = SchemaMetaNameBinder.defaultContext();
        ValueReaderFactoryRegistry objectFactories = ValueReaderFactoryRegistry.bind(context);
        TsonCompiledRegistry registry = new TsonCompiledRegistry(objectFactories);
        DefaultTsonCompiledSchemaLoader loader = new DefaultTsonCompiledSchemaLoader(registry, TsonBundledSchemas::fetch);

        // Registered via ordinary SchemaResolver.resolveSchema, not the raw bootstrap output --
        // TsonSchemaRegistry#register now refuses any self-referential schema with bootstrap() == true,
        // materialized or not (see its own Javadoc); resolveSchema never sets that flag.
        String metaKernelSource = TsonBundledSchemas.fetch(TsonBundledSchemas.META_KERNEL_ID);
        SchemaDocument metaKernelDocument = new TsonSchemaParser(metaKernelSource).parseSchemaDocument();
        TsonSchema metaKernel = new SchemaResolver(loader).resolveSchema(metaKernelDocument);
        registry.register(metaKernel, loader.load(TsonBundledSchemas.META_KERNEL_ID));

        TsonCompiledMetaSchema compiled = loader.load(TsonBundledSchemas.META_ID);

        assertEquals("BASE64", compiled.compiledSchema().get("binary_encoding")
                .read("BASE64"));
        // Still there, from the explicit pre-registration step above -- meta.tn's own resolution
        // didn't need to (and doesn't) re-register it.
        assertTrue(registry.get(TsonBundledSchemas.META_KERNEL_ID).isPresent());
    }
}
