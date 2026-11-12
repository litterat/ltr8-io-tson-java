package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.TestDocuments;
import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.compiler.TsonCompiledVocabularyRegistry;
import io.ltr8.tson.compiler.TsonCompiledVocabulary;
import io.ltr8.tson.compiler.TsonCompiledSchemaLoader;
import io.ltr8.tson.base.SchemaFetchException;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.TsonSchemaParser;
import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.TsonSchemaRegistry;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordExtensionType;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.VoidType;
import io.ltr8.tson.schema.meta.TypeChain;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the guarantee {@link SchemaResolver#resolveSchema(SchemaDocument)} relies on to derive
 * its own structure namespace -- a {@link TsonCompiledSchemaLoader} that already has the kernel, meta.tn,
 * registered and compiled can, given a document's own real {@code !!import} target (just
 * a URI of {@code document.imports()}), {@link TsonCompiledSchemaLoader#loadVocabulary} it and get back a
 * *compiled* vocabulary genuinely usable to read real data -- not merely present. Exercised
 * directly against the loader itself, not through the resolver, since that's exactly what {@code
 * resolveSchema} does internally with no further logic of its own: core.tn's own {@code !!import} target is the
 * kernel, which imports nothing. Also covers {@code resolveSchema} itself, separately, below.
 * Deliberately doesn't touch {@code bindAtomInstance} at all (see that
 * method's own Javadoc for why that's a separate, later step); this only proves the wiring to
 * *reach* an import's compiled vocabulary works.
 *
 * <p>Reading an entry that isn't itself a constructor (e.g. {@code
 * binary.bytes_encoding}, {@code meta.product_access_type}, {@code meta.top} -- ordinary declarations, not vocabulary
 * this codebase's own factories build against) goes through {@link
 * TsonCompiledVocabulary#compiledSchema()}'s own unscoped {@code get}, not {@link
 * TsonCompiledVocabulary#reader}, which is deliberately scoped to constructor-declared entries only
 * (see that method's own Javadoc).
 */
class TsonSchemaResolverCompiledVocabularyTest {

    /**
     * The kernel, meta.tn, and numeric.tn registered: their own instance declarations ({@code ieee_format =>
     * !enum [...]}) go through {@code resolveInstance}/{@code bindAtomInstance} as any schema's do, against the
     * readers of the loader's pre-loaded stand-in.
     *
     * <p><b>The kernel itself is registered via ordinary {@code SchemaResolver.resolveSchema}, not
     * the raw bootstrap output</b> ({@code TsonSchemaRegistry#register} refuses <i>any</i>
     * self-referential schema with {@code bootstrap() == true}, materialized or not). The loader's own
     * bootstrap branch supplies the compiled readers while resolving (so even {@code boolean =>
     * !enum [...]} resolves correctly despite the forward reference); that result carries no {@code
     * bootstrap} flag, so {@link TsonSchemaRegistry#register} accepts it, and {@code registry.register}
     * compiles it as the kernel's vocabulary. Mirrors {@code MetaTn1CompiledEndToEndTest#registerMeta}'s own
     * pattern.
     */
    private static TsonCompiledSchemaLoader loadMetaKernelAndMeta() {
        return loadMetaKernelAndMeta(TsonBundledSchemas::fetch);
    }

    /** The same, fetching through {@code source}. */
    private static TsonCompiledVocabularyRegistry loadMetaKernelAndMeta(SchemaSource source) {
        DataBindContext context = SchemaModelNameBinder.defaultContext();

        TsonCompiledVocabularyRegistry registry = new TsonCompiledVocabularyRegistry(context, source);
        TsonCompiledVocabularyRegistry loader = registry;

        String metaKernelSource = TsonBundledSchemas.fetch(TsonBundledSchemas.META_ID);
        SchemaDocument metaKernelDocument = new TsonSchemaParser(metaKernelSource).parseSchemaDocument();
        TsonSchema metaKernel = new SchemaResolver(loader).resolveSchema(metaKernelDocument);
        registry.register(metaKernel);

        loader.loadVocabulary(TsonBundledSchemas.META_ID);
        loader.loadVocabulary(TsonBundledSchemas.NUMERIC_ID);

        return loader;
    }

    /**
     * A standalone way to get a plain, registrable, non-bootstrap kernel {@link TsonSchema}
     * value -- resolved via its own throwaway object-mode loader (the only mode {@code
     * bindAtomInstance}'s own {@code (Top) vocabulary.reader(...).read(...)} cast can work against, for
     * the kernel's own {@code Instance} declarations), independent of whatever mode the *caller's*
     * own registry happens to use. Used by tests that need the kernel registered into a DOM-mode
     * registry for some *other* scenario they're testing (e.g. "meta.tn was never registered"),
     * where object mode would be beside the point.
     */
    private static TsonSchema resolveMetaKernelOrdinarily() {
        DataBindContext context = SchemaModelNameBinder.defaultContext();
        TsonCompiledVocabularyRegistry throwawayRegistry =
                new TsonCompiledVocabularyRegistry(context, TsonBundledSchemas::fetch);
        TsonCompiledSchemaLoader throwawayLoader = throwawayRegistry;

        String metaKernelSource = TsonBundledSchemas.fetch(TsonBundledSchemas.META_ID);
        SchemaDocument metaKernelDocument = new TsonSchemaParser(metaKernelSource).parseSchemaDocument();
        return new SchemaResolver(throwawayLoader).resolveSchema(metaKernelDocument);
    }

    @Test
    void coreTn1sOwnMetaTargetResolvesToMetaTn1sCompiledReader() {
        TsonCompiledSchemaLoader loader = loadMetaKernelAndMeta();
        SchemaDocument coreDocument = new TsonSchemaParser(TsonBundledSchemas.fetch(TsonBundledSchemas.CORE_ID)).parseSchemaDocument();

        // core's first !!import names numeric.tn with a ?sha256= pin -- compare by identity.
        assertEquals(CanonicalIdentity.canonicalize(TsonBundledSchemas.NUMERIC_ID),
                CanonicalIdentity.canonicalize(coreDocument.imports().getFirst()));

        TsonCompiledVocabulary compiledMeta = loader.loadVocabulary(coreDocument.imports().getFirst());

        assertTrue(compiledMeta.schema().entries().containsKey("atom.ordering"));
    }

    @Test
    void theCompiledVocabularyGenuinelyReadsRealData() {
        TsonCompiledSchemaLoader loader = loadMetaKernelAndMeta();
        SchemaDocument coreDocument = new TsonSchemaParser(TsonBundledSchemas.fetch(TsonBundledSchemas.CORE_ID)).parseSchemaDocument();

        TsonCompiledVocabulary compiledMeta = loader.loadVocabulary(coreDocument.imports().getFirst());
        Object result = compiledMeta.compiledSchema().get("atom.ordering")
                .read(TestDocuments.document("TOTAL"));

        assertEquals("TOTAL", result);
    }

    /** The kernel imports nothing: it is the bottom of every import chain, pre-loaded rather than resolved (§3.4). */
    @Test
    void theKernelImportsNothingAndItsCompiledReaderReadsRealData() {
        TsonCompiledSchemaLoader loader = loadMetaKernelAndMeta();
        SchemaDocument kernelDocument =
                new TsonSchemaParser(TsonBundledSchemas.fetch(TsonBundledSchemas.META_ID)).parseSchemaDocument();

        assertEquals(List.of(), kernelDocument.imports());
        TsonCompiledVocabulary compiledKernel = loader.loadVocabulary(TsonBundledSchemas.META_ID);
        assertTrue(compiledKernel.schema().entries().containsKey("numeric.integer_type"));
        assertEquals("INDEX", compiledKernel.compiledSchema().get("meta.product_access_type")
                .read(TestDocuments.document("INDEX")));
    }

    @Test
    void constructingWithoutALoaderThrowsClearly() {
        assertThrows(NullPointerException.class, () -> new SchemaResolver(null));
    }

    @Test
    void aLoaderThatNeverGotCoreRegisteredThrowsClearly() {
        TsonCompiledVocabularyRegistry registry = new TsonCompiledVocabularyRegistry(SchemaModelNameBinder.defaultContext());
        // The kernel only -- no core.tn -- resolved over its own freshly bootstrapped compiled form (loadVocabulary).
        registry.register(resolveMetaKernelOrdinarily());
        TsonCompiledSchemaLoader loader = registry;

        // core.tn isn't the kernel's own well-known bootstrap case, and the default SchemaSource
        // fetches nothing -- so this is exactly SchemaSource.registeredOnly()'s own rejection.
        SchemaFetchException thrown = assertThrows(SchemaFetchException.class,
                () -> loader.loadVocabulary(TsonBundledSchemas.CORE_ID));
        assertEquals(SchemaFetchException.Reason.NOT_PERMITTED, thrown.reason());
        assertTrue(thrown.getMessage().contains("core.tn"));
        assertTrue(thrown.getMessage().contains("no fetch capability"));
    }

    // ── resolveSchema(SchemaDocument)'s own validate-then-derive behavior ──

    private static final String MINI_DOCUMENT = """
            !!id:"https://example.test/mini.tn"
            !!import:"https://tson.io/2026/40/m/meta.tn"
            !schema {
              nothing => !meta.void_type {}
            }
            """;

    @Test
    void resolveSchemaTakesAnImportsNamespaceFromTheLoader() {
        SchemaResolver resolver = new SchemaResolver(loadMetaKernelAndMeta());
        SchemaDocument miniDocument = new TsonSchemaParser(MINI_DOCUMENT).parseSchemaDocument();

        // "meta.void_type" is not local to mini.tn -- reachable because its import, the kernel, contributes its
        // whole namespace.
        TsonSchema resolved = resolver.resolveSchema(miniDocument);

        Top voidDef = resolved.entries().get("nothing");
        assertEquals(new VoidType(), voidDef);
    }

    @Test
    void resolveSchemaThrowsClearlyWhenAnImportCantBeResolvedAtAll() {
        TsonCompiledVocabularyRegistry registry = new TsonCompiledVocabularyRegistry(SchemaModelNameBinder.defaultContext());
        SchemaResolver resolver = new SchemaResolver(registry);
        SchemaDocument miniDocument = new TsonSchemaParser(MINI_DOCUMENT.replace("m/meta.tn", "m/core.tn"))
                .parseSchemaDocument();

        // The default source fetches nothing, and core.tn is not the kernel the library serves from its own
        // bytes, so what fails is obtaining the import, not resolving it -- and SchemaSource.fetch names the
        // type that says so.
        SchemaFetchException thrown = assertThrows(SchemaFetchException.class,
                () -> resolver.resolveSchema(miniDocument));
        assertTrue(thrown.getMessage().contains("core.tn"));
    }

    /**
     * The definition mark and the selector are grammar ([TSON-SCHEMA] §5.2, §12.1), so they are read before
     * any import is consulted and lower under an import that declares no annotation at all -- the kernel
     * here. Nothing resolves them against a namespace, which is why no schema can give the
     * words another meaning and why no name has to be reserved to keep them.
     *
     * <p>Non-vacuous, and {@link #anOrdinaryUnknownAnnotationIsStillTheAuthorsError} is why: the same document
     * shape with a name the grammar does not read fails at exactly the check these bypass.
     */
    @Test
    void theMarksLowerUnderAnImportThatDeclaresNoneOfThem() {
        SchemaResolver resolver = new SchemaResolver(loadMetaKernelAndMeta());
        TsonSchema resolved = resolver.resolveSchema(new TsonSchemaParser(KERNEL_IMPORTING_MARKS)
                .parseSchemaDocument());

        RecordBody pet = assertInstanceOf(RecordBody.class, resolved.entries().get("pet"));
        assertEquals(RecordExtensionType.ABSTRACT, pet.extension());
        assertEquals(List.of("pet_type"), pet.discriminators());
        assertEquals(List.of("pet_type"), pet.discriminators());
        assertTrue(resolved.factsOf("pet").annotations().isEmpty(), "consumed, not preserved");
    }

    /** The check the marks bypass, shown still firing for a name that is not one. */
    @Test
    void anOrdinaryUnknownAnnotationIsStillTheAuthorsError() {
        SchemaResolver resolver = new SchemaResolver(loadMetaKernelAndMeta());
        SchemaDocument document = new TsonSchemaParser(KERNEL_IMPORTING_UNKNOWN_MARK).parseSchemaDocument();

        SchemaValidationException thrown = assertThrows(SchemaValidationException.class,
                () -> resolver.resolveSchema(document));
        assertTrue(thrown.getMessage().contains("does not name a type in this schema's namespace"),
                thrown.getMessage());
    }

    private static final String KERNEL_IMPORTING_MARKS = """
            !!id:"https://example.test/marks.tn"
            !!import:"https://tson.io/2026/40/m/meta.tn"
            !schema {
              pet => abstract { pet_type: meta.identifier =?  nick: meta.identifier }
            }
            """;

    private static final String KERNEL_IMPORTING_UNKNOWN_MARK = """
            !!id:"https://example.test/unknown-mark.tn"
            !!import:"https://tson.io/2026/40/m/meta.tn"
            !schema {
              pet => @totally_unknown_xyz { nick: meta.identifier }
            }
            """;

    private static final String MINI_DOCUMENT_NO_ID = """
            !!import:"https://tson.io/2026/40/m/meta.tn"
            !schema {
              nothing => !meta.void_type {}
            }
            """;

    /** A missing {@code !!id} is the author's error ([TSON-DATA] §2.2.1), never a fault in this library. */
    @Test
    void resolveSchemaRefusesAnAbsentIdAsASchemaError() {
        SchemaResolver resolver = new SchemaResolver(loadMetaKernelAndMeta());
        SchemaDocument noIdDocument = new TsonSchemaParser(MINI_DOCUMENT_NO_ID).parseSchemaDocument();

        assertTrue(noIdDocument.id().isEmpty());
        SchemaValidationException thrown =
                assertThrows(SchemaValidationException.class, () -> resolver.resolveSchema(noIdDocument));
        assertTrue(thrown.getMessage().contains("!!id"));
        assertTrue(thrown.getMessage().contains("absent"));
    }

    private static final String MINI_DOCUMENT_MALFORMED_ID = """
            !!id:"mini.tn"
            !!import:"https://tson.io/2026/40/m/meta.tn"
            !schema {
              nothing => !meta.void_type {}
            }
            """;

    @Test
    void resolveSchemaThrowsClearlyWhenIdIsNotAValidCanonicalIdentity() {
        SchemaResolver resolver = new SchemaResolver(loadMetaKernelAndMeta());
        SchemaDocument malformedIdDocument = new TsonSchemaParser(MINI_DOCUMENT_MALFORMED_ID).parseSchemaDocument();

        // "mini.tn" alone is a syntactically valid relative-reference URI, but has no scheme --
        // CanonicalIdentity.canonicalize's own rejection, surfaced here via CanonicalIdentity.validate.
        assertThrows(SchemaValidationException.class, () -> resolver.resolveSchema(malformedIdDocument));
    }

    private static final String MINI_DOCUMENT_MALFORMED_IMPORT = """
            !!id:"https://example.test/mini.tn"
            !!import:"https://tson.io/2026/40/m/meta.tn"
            !!import:"meta.tn"
            !schema {
              nothing => !meta.void_type {}
            }
            """;

    @Test
    void resolveSchemaThrowsClearlyWhenAnImportUriIsNotAValidCanonicalIdentity() {
        SchemaResolver resolver = new SchemaResolver(loadMetaKernelAndMeta());
        SchemaDocument malformedImportDocument = new TsonSchemaParser(MINI_DOCUMENT_MALFORMED_IMPORT).parseSchemaDocument();

        assertEquals(2, malformedImportDocument.imports().size());
        // "meta.tn" alone is a syntactically valid relative-reference URI, but has no scheme.
        assertThrows(SchemaValidationException.class, () -> resolver.resolveSchema(malformedImportDocument));
    }

    private static final String MINI_DOCUMENT_IMPORT_MERGED = """
            !!id:"https://example.test/mini.tn"
            !!import:"https://tson.io/2026/40/m/meta.tn"
            !schema {
              my_type => meta.atom & {}
            }
            """;

    @Test
    void resolveSchemaGenuinelyMergesImportedEntriesIntoTheTypeNameNamespace() {
        // A bare type-ref (§8.3) is carried through unverified regardless of whether the target
        // exists anywhere, so that alone wouldn't prove anything -- composition is the real test:
        // resolveComposition does exactly one resolved.get(supertypeName), no fallback, so "meta.atom"
        // (the kernel's own, zero fields) is only findable here if !!import's own entries were
        // genuinely merged into the namespace, not just validated as well-formed URIs.
        SchemaResolver resolver = new SchemaResolver(loadMetaKernelAndMeta());
        SchemaDocument miniDocument = new TsonSchemaParser(MINI_DOCUMENT_IMPORT_MERGED).parseSchemaDocument();

        TsonSchema resolved = resolver.resolveSchema(miniDocument);

        // Transitive, per SchemaResolver's own induction: direct supertype + its own supertype chain.
        assertEquals(List.of("meta.atom", "meta.top", "meta.type"),
                TypeChain.supertypesOf(resolved.entries().get("my_type")));
        // Imported entries are visible during resolution but never part of the result itself.
        assertEquals(Set.of("my_type"), resolved.entries().keySet());
    }

    private static final String MINI_DOCUMENT_IMPORT_COLLIDES_WITH_LOCAL = """
            !!id:"https://example.test/mini.tn"
            !!import:"https://tson.io/2026/40/m/meta.tn"
            !schema {
              meta => !meta.void_type {}
            }
            """;

    @Test
    void resolveSchemaRejectsALocalDeclarationCollidingWithAnImportedName() {
        // The kernel declares "meta", the schema map holding all of its entries -- redeclaring it locally while
        // also importing the kernel is SchemaValidator's own "collides with an entry of the same name brought in
        // by !!import" rule, caught here one stage earlier.
        SchemaResolver resolver = new SchemaResolver(loadMetaKernelAndMeta());
        SchemaDocument miniDocument = new TsonSchemaParser(MINI_DOCUMENT_IMPORT_COLLIDES_WITH_LOCAL).parseSchemaDocument();

        SchemaValidationException thrown = assertThrows(
                SchemaValidationException.class, () -> resolver.resolveSchema(miniDocument));
        assertTrue(thrown.getMessage().contains("meta"));
        assertTrue(thrown.getMessage().contains("!!import"));
    }

    private static final String MINI_DOCUMENT_DIAMOND_IMPORT = """
            !!id:"https://example.test/mini.tn"
            !!import:"https://tson.io/2026/40/m/meta.tn"
            !!import:"https://tson.io/2026/40/m/meta.tn"
            !schema {
              placeholder => unit
            }
            """;

    /**
     * A diamond: this document imports the kernel twice. Every one of the kernel's names therefore arrives by two
     * routes -- but
     * they are one schema's entries, so they unify rather than colliding, and {@code unit} resolves
     * (§2.2.3). A name-occurrence collision rule rejects this document instead, which
     * is what makes the diamond unauthorable for every practical schema.
     */
    @Test
    void resolveSchemaUnifiesASchemaReachedByTwoImportRoutes() {
        SchemaResolver resolver = new SchemaResolver(loadMetaKernelAndMeta());
        SchemaDocument miniDocument = new TsonSchemaParser(MINI_DOCUMENT_DIAMOND_IMPORT).parseSchemaDocument();

        TsonSchema resolved = resolver.resolveSchema(miniDocument);

        assertTrue(resolved.entries().containsKey("placeholder"),
                () -> "'unit' arrives through both imports and must unify: " + resolved.entries().keySet());
    }

    /**
     * One namespace per schema ([TSON-SCHEMA] §3.3): a schema importing the kernel alone, and one importing the
     * kernel and core.tn, each resolve in one namespace.
     */
    @Test
    void resolveSchemaAdmitsTheKernelAloneOrWithCore() {
        SchemaResolver resolver = new SchemaResolver(loadMetaKernelAndMeta());
        String importingTheKernel = """
                !!id:"https://example.test/mini.tn"
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !schema { my_type => { v: meta.identifier } }
                """;
        String importingTheKernelAndCore = """
                !!id:"https://example.test/mini.tn"
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !!import:"https://tson.io/2026/40/m/core.tn"
                !schema { my_type => { v: text } }
                """;
        for (String source : List.of(importingTheKernel, importingTheKernelAndCore)) {
            SchemaDocument document = new TsonSchemaParser(source).parseSchemaDocument();

            assertTrue(resolver.resolveSchema(document).entries().containsKey("my_type"));
        }
    }

    /**
     * Every edge the bundled chain and a vocabulary schema use links: policy.tn importing core.tn, a vocabulary
     * schema importing the kernel, and a schema importing that one and core.tn.
     */
    @Test
    void theBundledChainAndAVocabularyImportStillLink() {
        Map<String, String> sources = Map.of(
                "https://example.test/meta-api.tn", """
                        !!id:"https://example.test/meta-api.tn"
                        !!import:"https://tson.io/2026/40/m/meta.tn"
                        !schema { status_name => meta.identifier }
                        """,
                "https://example.test/orders.tn", """
                        !!id:"https://example.test/orders.tn"
                        !!import:"https://example.test/meta-api.tn"
                        !!import:"https://tson.io/2026/40/m/core.tn"
                        !schema { order => { sku: text  quantity: int32 } }
                        """);
        SchemaSource local = SchemaSource.ofMap(sources);
        TsonCompiledVocabularyRegistry registry = loadMetaKernelAndMeta(uri -> uri.contains("example.test")
                ? local.fetch(uri) : TsonBundledSchemas.fetch(uri.replaceFirst("\\?.*", "")));

        assertNotNull(registry.loadVocabulary(TsonBundledSchemas.META_ID));
        assertNotNull(registry.resolveLinked(TsonBundledSchemas.POLICY_ID));
        assertTrue(registry.resolveLinked("https://example.test/orders.tn").schema().entries().containsKey("order"));
    }

    // ── TsonCompiledVocabularyRegistry's own bootstrap behavior ──

    @Test
    void loaderBootstrapsMetaKernelFromAnEmptyRegistryWithNoInfiniteLoop() {
        TsonCompiledVocabularyRegistry registry = new TsonCompiledVocabularyRegistry(SchemaModelNameBinder.defaultContext());
        TsonCompiledSchemaLoader loader = registry;

        // The kernel is its own vocabulary -- if resolve() ever fell through to the generic
        // fetch-and-resolve-via-SchemaResolver(this) path for this URI, this call would recurse
        // forever (resolveSchema -> loader.loadVocabulary(sameUri) -> ...).
        // Completing at all is the proof; the assertions below just confirm it's genuinely usable.
        TsonCompiledVocabulary compiled = loader.loadVocabulary(TsonBundledSchemas.META_ID);

        // The same entry set a genuinely registered kernel has: the bootstrap desugars its own document,
        // so TsonSchemaLinker.linkBootstrap has nothing left to add. Never registered (see the next test).
        assertEquals(82, compiled.schema().entries().size());
        // Genuinely usable: a concrete entry reads cleanly (the marker root `top` deliberately can't be
        // read without an explicit type-ref, so it isn't the check here).
        assertNotNull(compiled.compiledSchema().get("numeric.integer_size")
                .read(TestDocuments.document("{ bits: 8 signed: true }")));
    }

    @Test
    void loaderBuildsTheBootstrapOnceAndNeverRegistersIt() {
        // The bootstrap stand-in is built once per registry and answers every lookup, but is never registered
        // -- the permanent, materialized registry entry for the kernel comes from a separate, deliberate
        // "load and register" step done once elsewhere.
        TsonCompiledVocabularyRegistry registry = new TsonCompiledVocabularyRegistry(SchemaModelNameBinder.defaultContext());
        TsonCompiledSchemaLoader loader = registry;

        TsonCompiledVocabulary first = loader.loadVocabulary(TsonBundledSchemas.META_ID);
        TsonCompiledVocabulary second = loader.loadVocabulary(TsonBundledSchemas.META_ID);

        assertSame(first, second);
        assertTrue(registry.get(TsonBundledSchemas.META_ID).isEmpty());
        assertTrue(registry.schemaRegistry().get(TsonBundledSchemas.META_ID).isEmpty());
    }

    @Test
    void loaderWithTheDefaultSourceThrowsClearlyForAnUnregisteredNonBootstrapUri() {
        TsonCompiledVocabularyRegistry registry = new TsonCompiledVocabularyRegistry(SchemaModelNameBinder.defaultContext());
        TsonCompiledSchemaLoader loader = registry;

        SchemaFetchException thrown = assertThrows(SchemaFetchException.class,
                () -> loader.loadVocabulary("https://tson.io/2026/40/m/core.tn"));
        assertTrue(thrown.getMessage().contains("no fetch capability"));
    }

    @Test
    void loaderResolvesANonBootstrapUriGenericallyViaAPluggedInSchemaSource() {
        // The kernel registered explicitly, through ordinary SchemaResolver.resolveSchema rather than the raw
        // bootstrap output (TsonSchemaRegistry#register refuses a schema with bootstrap() == true), and then
        // served from the registry, not from the one-off bootstrap stand-in.
        DataBindContext context = SchemaModelNameBinder.defaultContext();
        TsonCompiledVocabularyRegistry registry = new TsonCompiledVocabularyRegistry(context, TsonBundledSchemas::fetch);
        TsonCompiledSchemaLoader loader = registry;

        String metaKernelSource = TsonBundledSchemas.fetch(TsonBundledSchemas.META_ID);
        SchemaDocument metaKernelDocument = new TsonSchemaParser(metaKernelSource).parseSchemaDocument();
        TsonSchema metaKernel = new SchemaResolver(loader).resolveSchema(metaKernelDocument);
        registry.register(metaKernel);

        TsonCompiledVocabulary compiled = loader.loadVocabulary(TsonBundledSchemas.META_ID);

        assertEquals("TOTAL", compiled.compiledSchema().get("atom.ordering")
                .read(TestDocuments.document("TOTAL")));
        // Still there, from the explicit registration step above.
        assertTrue(registry.get(TsonBundledSchemas.META_ID).isPresent());
    }
}
