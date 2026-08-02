package io.ltr8.tson.compiler.config;

import io.ltr8.tson.compiler.ContentHash;
import io.ltr8.tson.compiler.ContentHashMismatchException;
import io.ltr8.tson.compiler.TsonCompiledMetaSchema;
import io.ltr8.tson.compiler.TsonCompiledSchema;
import io.ltr8.tson.compiler.TsonCompiledSchemaLoader;
import io.ltr8.tson.compiler.TsonSchemaCompiler;
import io.ltr8.tson.compiler.TsonSchemaParser;
import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.compiler.resolver.MetaKernelBootstrapResolver;
import io.ltr8.tson.compiler.resolver.SchemaResolver;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.TsonSchemaLinker;
import io.ltr8.tson.schema.TsonSchemaRegistry;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The compiled-schema registry, and the on-demand {@link TsonCompiledSchemaLoader} that fills it. It
 * pairs one-to-one with a {@link TsonSchemaRegistry}'s store of resolved schemas, adding the compiled
 * half: a meta-layer schema (its own {@code !!meta} is meta-kernel) is stored as the {@link
 * TsonCompiledMetaSchema} subtype -- the only kind able to govern another schema's compilation -- and
 * every other schema as a bare {@link TsonCompiledSchema}. {@link #getMeta} narrows a lookup to the
 * governing subtype, so a non-meta schema can never be handed back where a governing meta is required.
 *
 * <p><b>As a loader</b> ({@link #load}/{@link #loadMeta}) it resolves a schema on demand from its URI,
 * so a resolver reaching a {@code !!meta}/{@code !!import} target needn't have it pre-registered. Three
 * cases, in order: a cache hit ({@link #get}); meta-kernel's own well-known identity, answered by its
 * hand-written bootstrap and never cached (never through the generic path below, which would recurse
 * forever on meta-kernel's self-naming {@code !!meta}, §1.5); otherwise fetch via the configured {@link
 * TsonSchemaSource}, parse, resolve via a fresh {@code SchemaResolver} bound to this same loader (so the
 * document's own {@code !!meta}/{@code !!import} resolve recursively, cache-then-bootstrap-then-fetch all
 * the way down), then {@link #register} -- which is the only result that is cached. Content hashes are
 * recorded and verified per identity along the way ([TSON-DATA] §2.2.1, [TSON-SCHEMA] §10.2).
 *
 * <p><b>{@link #withStandardLibrary} is the ordinary entry point</b>: it builds a registry with the three
 * bundled schemas (meta-kernel, meta, core) already loaded -- fetched straight from {@link
 * TsonBundledSchemas}, so it works whatever the configured source. The plain constructors leave the
 * registry empty, for a caller that populates it itself. Any schema governed by (or importing) the
 * bundled three then reuses what's already in {@link #get} rather than recompiling its chain.
 *
 * <p><b>Keyed by canonical identity</b> ({@link TsonSchemaRegistry#canonicalIdentity}, scheme and query
 * stripped), matching the paired {@link TsonSchemaRegistry}. So two differently-spelled-but-equivalent
 * URIs for one schema -- in particular a hash-pinned {@code ?sha256=} reference and a plain one -- find
 * the same entry, which is what lets a pinned reference resolve against an already-registered schema
 * without re-fetching or double-registering ([TSON-DATA] §2.2.1: the pin is verification metadata, not
 * identity).
 *
 * <p>Not thread-safe: {@link #register}/{@link #get}/{@link #getMeta} are {@code synchronized}, but
 * {@link #load} (and its content-hash bookkeeping) is not -- matching {@link TsonSchemaRegistry}'s own
 * stated guarantee, no stronger.
 */
public final class TsonCompiledRegistry implements TsonCompiledSchemaLoader {

    private static final String META_KERNEL_IDENTITY =
            TsonSchemaRegistry.canonicalIdentity(TsonBundledSchemas.META_KERNEL_ID);

    private final TsonSchemaRegistry schemaRegistry;
    private final ValueReaderFactoryResolver resolver;
    private final TsonSchemaSource source;
    private final Map<String, TsonCompiledSchema> compiled = new LinkedHashMap<>();
    // Content hash per canonical identity, recorded when an identity is first resolved. Every
    // hash-pinned reference to an identity is verified against it -- so conflicting pins for one
    // identity error (at most one can match the content) and a plain reference resolves to the
    // verified instance ([TSON-SCHEMA] §10.2's per-identity verification).
    private final Map<String, String> contentHashes = new HashMap<>();

    /**
     * A fresh, empty {@link TsonSchemaRegistry} of its own and no fetch capability -- the common case for a
     * caller that owns the whole registration+compilation pipeline and only resolves the bundled standard library.
     */
    public TsonCompiledRegistry(ValueReaderFactoryResolver resolver) {
        this(new TsonSchemaRegistry(), resolver);
    }

    /** As above but sharing an existing {@link TsonSchemaRegistry}, still with no fetch capability. */
    public TsonCompiledRegistry(TsonSchemaRegistry schemaRegistry, ValueReaderFactoryResolver resolver) {
        this(schemaRegistry, resolver, TsonSchemaSource.registeredOnly());
    }

    /** A fresh, empty {@link TsonSchemaRegistry} of its own, with a fetch {@code source}. */
    public TsonCompiledRegistry(ValueReaderFactoryResolver resolver, TsonSchemaSource source) {
        this(new TsonSchemaRegistry(), resolver, source);
    }

    /**
     * @param source where {@link #load} fetches a not-yet-registered schema's source text from -- {@link
     *     TsonSchemaSource#registeredOnly()} by default, so nothing is fetched unless a caller opts in.
     */
    public TsonCompiledRegistry(TsonSchemaRegistry schemaRegistry, ValueReaderFactoryResolver resolver,
                                TsonSchemaSource source) {
        this.schemaRegistry = schemaRegistry;
        this.resolver = resolver;
        this.source = source;
    }

    /**
     * The paired {@link TsonSchemaRegistry} -- a caller resolving a declaration against one of the schemas
     * registered here (structure namespace, {@code !!import}, ...) reads its resolved entries from there, same
     * as always; this registry only adds the compiled half.
     */
    public TsonSchemaRegistry schemaRegistry() {
        return schemaRegistry;
    }

    /**
     * The {@link ValueReaderFactoryResolver} every schema here is compiled with -- e.g. so a caller can compile
     * a one-off reader (such as meta-kernel's own bootstrap, via {@link TsonCompiledMetaSchema#bootstrap}) with
     * the same factories, without registering or caching it here.
     */
    public ValueReaderFactoryResolver resolver() {
        return resolver;
    }

    /**
     * A registry with this library's three bundled schemas -- meta-kernel, meta, core -- already loaded,
     * plus {@code source} for any other, non-bundled URIs a caller later resolves. This is the ordinary
     * way to get a working registry; the plain constructors leave it empty (for a caller that populates
     * it itself, e.g. a test bootstrapping in isolation).
     */
    public static TsonCompiledRegistry withStandardLibrary(ValueReaderFactoryResolver resolver, TsonSchemaSource source) {
        TsonCompiledRegistry registry = new TsonCompiledRegistry(resolver, source);
        registry.loadStandardLibrary();
        return registry;
    }

    /**
     * Loads this library's three bundled schema documents into this registry in dependency order, so any
     * schema governed by (or importing) them resolves. Each is fetched straight from {@link
     * TsonBundledSchemas} (never the configured {@code source}) and registered; its own {@code
     * !!meta}/{@code !!import} targets are cache hits by the time they're needed (meta-kernel's own is
     * its self-referential bootstrap case, resolved ordinarily and registered explicitly since {@link
     * #load} never caches that identity -- see that method).
     */
    private void loadStandardLibrary() {
        registerBundled(TsonBundledSchemas.META_KERNEL_ID);
        registerBundled(TsonBundledSchemas.META_ID);
        registerBundled(TsonBundledSchemas.CORE_ID);
    }

    /**
     * Fetches one bundled schema's own source straight from {@link TsonBundledSchemas}, records its content
     * hash (so a later hash-pinned reference to it is verified), resolves it against this registry (its
     * {@code !!meta}/{@code !!import} targets already loaded), and registers it.
     */
    private void registerBundled(String id) {
        String sourceText = TsonBundledSchemas.fetch(id);
        recordAndVerify(sourceText, id, TsonSchemaRegistry.canonicalIdentity(id));
        SchemaDocument document = new TsonSchemaParser(sourceText).parseSchemaDocument();
        TsonSchema resolved = new SchemaResolver(this).resolveSchema(document);
        register(resolved, loadMeta(document.meta()));
    }

    /**
     * Resolves {@code uri} to its compiled form, fetching/resolving/registering/compiling on demand --
     * see this class's own "As a loader" note for the three cases (cache hit, meta-kernel bootstrap,
     * generic fetch). Deliberately not {@code synchronized}: it recurses into itself (via {@code
     * SchemaResolver} resolving the document's own {@code !!meta}/{@code !!import}), and holding a lock
     * across a whole fetch would serialize unrelated loads for no real benefit.
     */
    @Override
    public TsonCompiledSchema load(String uri) {
        String identity = TsonSchemaRegistry.canonicalIdentity(uri);
        Optional<TsonCompiledSchema> cached = get(uri);
        if (cached.isPresent()) {
            // Already resolved: verify *this* reference's own pin against the identity's content hash.
            // A conflicting pin (a different digest than the one this identity was verified against)
            // errors here rather than silently resolving to the cached instance (§10.2).
            verifyPin(uri, identity);
            return cached.get();
        }
        if (META_KERNEL_IDENTITY.equals(identity)) {
            TsonSchema metaKernel = MetaKernelBootstrapResolver.getMetaKernelSchema();
            // TsonSchemaLinker.linkBootstrap runs its own materialization pass (synthesizing entries
            // for argument-bearing type-refs like enum's own `members: set<token>`) before compiling,
            // but persists nothing (not register -- TsonSchemaRegistry refuses a linked bootstrap
            // schema outright, always), so this is discarded immediately after: every call still
            // re-bootstraps and re-links from scratch, every time -- only the *quality* of the
            // one-off result changes (58 entries, not 49), not its lifetime. The permanent, shared
            // registry entry for meta-kernel comes from an explicit "load it and register it" step
            // done once elsewhere; until then this one-off bootstrap stands in so nothing is ever
            // left unable to resolve at all.
            recordAndVerify(TsonBundledSchemas.fetch(TsonBundledSchemas.META_KERNEL_ID), uri, identity);
            TsonLinkedSchema linked = TsonSchemaLinker.linkBootstrap(metaKernel);
            return TsonCompiledMetaSchema.bootstrap(linked, resolver);
        }
        String sourceText = source.fetch(uri);
        // Record this identity's content hash (first resolution) and verify this reference's pin
        // against it -- §2.2.1's MUST-verify rule. A transitive pinned !!import/!!meta is verified
        // likewise when its own load(...) reaches here.
        recordAndVerify(sourceText, uri, identity);
        SchemaDocument document = new TsonSchemaParser(sourceText).parseSchemaDocument();
        crossCheckId(document, uri, identity);
        TsonSchema resolved = new SchemaResolver(this).resolveSchema(document);
        // resolveSchema already called loadMeta(document.meta()) internally to build its own structure
        // namespace, so this is a cache hit, not a second compile -- register still needs the governing
        // TsonCompiledMetaSchema itself as its second argument, which resolveSchema has no way to hand
        // back (it returns only the resolved TsonSchema).
        TsonCompiledMetaSchema governingMeta = loadMeta(document.meta());
        return register(resolved, governingMeta);
    }

    /**
     * Links {@code schema} (via {@link TsonSchemaLinker#link}, using the paired {@link
     * #schemaRegistry} itself as the lookup source for {@code !!import}/{@code !!meta} targets) and
     * registers the result (via {@link TsonSchemaRegistry#register}, so the usual {@code
     * !!import}-merging/reference-validation rules all apply exactly as they would calling that
     * directly), then compiles the *registered* result against {@code governingMeta} (never the raw
     * input -- a compiled reader needs linking already done) and stores it keyed by {@code schema}'s
     * own {@code !!id}.
     *
     * <p>A meta-layer schema (its own {@code !!meta} is meta-kernel) is wrapped as a {@link
     * TsonCompiledMetaSchema} so it can go on to govern others; every other schema is stored as the
     * bare {@link TsonCompiledSchema} it compiled to. The result is returned directly, so the
     * immediate next schema in a governing chain (which needs a governing meta -- reachable via {@link
     * #getMeta}) doesn't have to look it up again.
     *
     * @param governingMeta the already-compiled meta-schema {@code schema.meta()} names -- meta-
     *                       kernel's own case aside (see {@link TsonCompiledMetaSchema#bootstrap}),
     *                       always a previous call's own {@link #getMeta} result
     */
    public synchronized TsonCompiledSchema register(TsonSchema schema, TsonCompiledMetaSchema governingMeta) {
        TsonLinkedSchema linked = TsonSchemaLinker.link(schema, schemaRegistry);
        TsonLinkedSchema registered = schemaRegistry.register(linked);
        TsonCompiledSchema compiledSchema = TsonSchemaCompiler.compile(registered, governingMeta);
        TsonCompiledSchema result = isMetaLayer(registered.schema())
                ? new TsonCompiledMetaSchema(compiledSchema, resolver)
                : compiledSchema;
        compiled.put(TsonSchemaRegistry.canonicalIdentity(registered.schema().id()), result);
        return result;
    }

    /**
     * {@code id} is matched by canonical identity ({@link TsonSchemaRegistry#canonicalIdentity}), so any
     * spelling -- pinned or plain -- of a registered schema's own {@code !!id} finds it.
     */
    public synchronized Optional<TsonCompiledSchema> get(String id) {
        return Optional.ofNullable(compiled.get(TsonSchemaRegistry.canonicalIdentity(id)));
    }

    /**
     * As {@link #get}, narrowed to a governing meta-schema: present only when {@code id} names a registered
     * meta-layer schema. A non-meta schema is never handed back here, so it can't be passed where a governing
     * meta is required.
     */
    public synchronized Optional<TsonCompiledMetaSchema> getMeta(String id) {
        return get(id).filter(TsonCompiledMetaSchema.class::isInstance).map(TsonCompiledMetaSchema.class::cast);
    }

    /**
     * A meta-layer schema is one whose own {@code !!meta} names meta-kernel (§9's meta layer: meta-kernel and
     * meta). Only such a schema compiles to a {@link TsonCompiledMetaSchema} and may govern others.
     */
    private static boolean isMetaLayer(TsonSchema schema) {
        return TsonSchemaRegistry.canonicalIdentity(schema.meta())
                .equals(TsonSchemaRegistry.canonicalIdentity(TsonBundledSchemas.META_KERNEL_ID));
    }

    /**
     * A document obtained via a reference must own the identity it was fetched under: its embedded
     * {@code !!id} canonical identity MUST equal the reference's ([TSON-DATA] §2.2.1), so a source can't
     * return content under the wrong identity. A hash-pinned reference's target MUST carry an {@code
     * !!id} at all; a plain reference to an id-less development artifact is allowed here (registration
     * requires an id separately).
     */
    private void crossCheckId(SchemaDocument document, String referenceUri, String identity) {
        if (document.id().isEmpty()) {
            if (ContentHash.declaredSha256(referenceUri).isPresent()) {
                throw new IllegalStateException("the hash-pinned reference \"" + referenceUri
                        + "\" resolved to a document with no !!id -- a hashed reference's target must carry one "
                        + "([TSON-DATA] §2.2.1)");
            }
            return;
        }
        String embedded = TsonSchemaRegistry.canonicalIdentity(document.id().get());
        if (!embedded.equals(identity)) {
            throw new IllegalStateException("identity mismatch: reference \"" + referenceUri + "\" (identity \""
                    + identity + "\") resolved to a document whose own !!id is \"" + document.id().get()
                    + "\" (identity \"" + embedded + "\") -- refusing content obtained under the wrong identity "
                    + "([TSON-DATA] §2.2.1)");
        }
    }

    /**
     * Verify {@code uri}'s own pin against the fetched content, then record the content hash for the
     * identity -- verification *first*, so a rejected fetch records nothing and cannot poison the
     * identity's cache entry for a later, valid one (§10.2 caching semantics). {@code putIfAbsent}
     * keeps the first-resolved (known-good) hash immutable thereafter.
     */
    private void recordAndVerify(String sourceText, String uri, String identity) {
        String contentHash = ContentHash.sha256(sourceText.getBytes(StandardCharsets.UTF_8));
        // A pre-loaded bundled schema ships with a digest the library holds (§10.2): the shipped bytes
        // MUST match it -- the authoritative digest a pinned reference to it is checked against, and an
        // integrity check that the packaged resource is the one the library was built for.
        TsonBundledSchemas.declaredSha256(uri).ifPresent(held -> {
            if (!held.equals(contentHash)) {
                throw new IllegalStateException("bundled schema \"" + identity + "\" content hashes to "
                        + contentHash + " but the library holds digest " + held
                        + " -- the packaged resource does not match its published digest");
            }
        });
        checkPin(uri, contentHash, identity);
        contentHashes.putIfAbsent(identity, contentHash);
    }

    /** Verify a reference's declared {@code ?sha256=} pin, if any, against the identity's already-recorded content hash. */
    private void verifyPin(String referenceUri, String identity) {
        String contentHash = contentHashes.get(identity);
        if (contentHash != null) {
            checkPin(referenceUri, contentHash, identity);
        }
    }

    /** A reference's declared {@code ?sha256=} pin, if present, MUST equal {@code contentHash}. */
    private static void checkPin(String referenceUri, String contentHash, String identity) {
        ContentHash.declaredSha256(referenceUri).ifPresent(declared -> {
            if (!declared.equals(contentHash)) {
                throw new ContentHashMismatchException("content hash mismatch for \"" + referenceUri
                        + "\": the reference declares sha256=" + declared + " but the content for identity \""
                        + identity + "\" hashes to " + contentHash
                        + " -- refusing to use mismatched content ([TSON-DATA] §2.2.1, [TSON-SCHEMA] §10.2)");
            }
        });
    }
}
