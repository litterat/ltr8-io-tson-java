package io.ltr8.tson.compiler;

import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.compiler.reader.ValueReaderFactoryRegistry;
import io.ltr8.tson.compiler.reader.ValueReaderFactoryResolver;
import io.ltr8.tson.compiler.resolver.MetaKernelBootstrapResolver;
import io.ltr8.tson.compiler.resolver.SchemaResolver;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonCanonicalIdentity;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.TsonSchemaRegistry;
import io.ltr8.tson.schema.TsonSchemaValidationException;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The compiled <b>meta-schema</b> registry, and the on-demand {@link TsonCompiledSchemaLoader} that fills
 * it. It pairs with a {@link TsonSchemaRegistry}'s store of resolved schemas, adding the compiled half for
 * the meta layer only: it compiles and caches exactly the meta-layer schemas (their own {@code !!meta} is
 * meta-kernel -- meta-kernel and meta.tn), the only kind able to govern another schema's compilation. Every
 * other schema (core.tn, a user schema) is only <i>resolved</i> here -- registered into the paired {@link
 * TsonSchemaRegistry} via {@link #resolveLinked} -- and compiled per mode in a {@link
 * TsonCompiledSchemaRegistry}, never in this core.
 *
 * <p><b>As a loader</b> it resolves a schema on demand from its URI, so a resolver reaching a {@code
 * !!meta}/{@code !!import} target needn't have it pre-registered -- {@link #loadMeta} for a governing meta
 * (compiled), {@link #resolveLinked} for an import or user schema (resolved only). Both, in order: a cache
 * hit; meta-kernel's own well-known identity, answered by its hand-written bootstrap and never cached (its
 * self-naming {@code !!meta} would recurse forever through the generic path, §1.5); otherwise fetch via the
 * configured {@link TsonSchemaSource}, parse, resolve via a fresh {@code SchemaResolver} bound to this same
 * loader (so the document's own {@code !!meta}/{@code !!import} resolve recursively, all the way down).
 * Content hashes are recorded and verified per identity along the way ([TSON-DATA] §2.2.1, [TSON-SCHEMA]
 * §10.2).
 *
 * <p><b>{@link #withStandardLibrary} is the ordinary entry point</b>: it builds a registry with the three
 * bundled schemas (meta-kernel, meta, core) already loaded -- fetched straight from {@link
 * TsonBundledSchemas}, so it works whatever the configured source. The plain constructors leave the
 * registry empty, for a caller that populates it itself. Any schema governed by (or importing) the
 * bundled three then reuses what's already in {@link #get} rather than recompiling its chain.
 *
 * <p><b>Keyed by canonical identity</b> ({@link TsonCanonicalIdentity#canonicalize}, scheme and query
 * stripped), matching the paired {@link TsonSchemaRegistry}. So two differently-spelled-but-equivalent
 * URIs for one schema -- in particular a hash-pinned {@code ?sha256=} reference and a plain one -- find
 * the same entry, which is what lets a pinned reference resolve against an already-registered schema
 * without re-fetching or double-registering ([TSON-DATA] §2.2.1: the pin is verification metadata, not
 * identity).
 *
 * <p><b>Concurrent resolution of one identity is safe; it is not serialized.</b> {@link #register}/{@link
 * #get} are {@code synchronized} and the content-hash map is concurrent, but {@link #loadMeta}/{@link
 * #resolveLinked} deliberately are not: they recurse into themselves and holding a lock across a fetch
 * would serialize unrelated loads. So two threads reaching the same cold identity both do the work, and the
 * caches settle it -- {@link TsonSchemaRegistry#registerIfAbsent} and {@link #compileAndCache} both keep the
 * first entry and hand it to everyone, rather than failing the loser or leaving two equivalent instances
 * for one identity. What is duplicated on a race is work, never state.
 *
 * <p>Explicit registration keeps its strictness: {@link #register} and {@code TsonSchemaRegistry.register}
 * still reject an identity that is already there, because registering the same schema twice on purpose is a
 * caller error however many threads are involved.
 */
public final class TsonCompiledMetaRegistry implements TsonCompiledSchemaLoader {

    private static final String META_KERNEL_IDENTITY =
            TsonCanonicalIdentity.canonicalize(TsonBundledSchemas.META_KERNEL_ID);

    private final TsonSchemaRegistry schemaRegistry;
    private final ValueReaderFactoryResolver resolver;
    private final TsonSchemaSource source;
    private final Map<String, TsonCompiledMetaSchema> compiled = new LinkedHashMap<>();
    // Content hash per canonical identity, recorded when an identity is first resolved. Every
    // hash-pinned reference to an identity is verified against it -- so conflicting pins for one
    // identity error (at most one can match the content) and a plain reference resolves to the
    // verified instance ([TSON-SCHEMA] §10.2's per-identity verification).
    //
    // Concurrent, because resolveLinked -- which writes here -- is deliberately not synchronized. A plain
    // map loses entries under a concurrent put, and a lost entry here does not fail loudly: verifyPin finds
    // no hash for the identity and silently verifies nothing, which is §10.2's MUST quietly skipped.
    private final Map<String, String> contentHashes = new ConcurrentHashMap<>();

    /**
     * The identities this thread is part-way through resolving, outermost first -- [TSON-DATA] §2.2.3's
     * import-cycle guard, and the {@code !!meta} chain's too ({@link #loadMeta} reaches every link through
     * {@link #resolveLinked}).
     *
     * <p><b>Why a guard is needed at all:</b> an {@code !!import} is resolved through this registry, and a
     * schema is registered only once it is fully linked -- so while {@code a.tn} is resolving it is in no
     * cache, and {@code b.tn} importing it back re-enters {@link #resolveLinked} for an identity already in
     * flight. Unguarded that is unbounded recursion ending in a {@link StackOverflowError}: an {@link Error}
     * from ordinary author input, which no diagnostic ever sees and which the exception policy cannot
     * classify.
     *
     * <p><b>Per thread, not per registry.</b> Concurrent resolution of one identity by two threads is
     * documented as safe here and settles at the caches; a registry-wide set would make the second thread's
     * ordinary in-flight entry look like a cycle to the first. Recursion through {@code !!import}/{@code
     * !!meta} is strictly within one thread, so per-thread is both correct and exactly the scope of the
     * question being asked.
     */
    private final ThreadLocal<Set<String>> resolving = ThreadLocal.withInitial(LinkedHashSet::new);

    /**
     * A fresh, empty {@link TsonSchemaRegistry} of its own and no fetch capability -- the common case for a
     * caller that owns the whole registration+compilation pipeline and only resolves the bundled standard library.
     */
    public TsonCompiledMetaRegistry(DataBindContext context) {
        this(new TsonSchemaRegistry(), context);
    }

    /** As above but sharing an existing {@link TsonSchemaRegistry}, still with no fetch capability. */
    public TsonCompiledMetaRegistry(TsonSchemaRegistry schemaRegistry, DataBindContext context) {
        this(schemaRegistry, context, TsonSchemaSource.registeredOnly());
    }

    /** A fresh, empty {@link TsonSchemaRegistry} of its own, with a fetch {@code source}. */
    public TsonCompiledMetaRegistry(DataBindContext context, TsonSchemaSource source) {
        this(new TsonSchemaRegistry(), context, source);
    }

    /**
     * @param context the object-binding context this registry compiles governing meta-schemas with -- always
     *     object-binding mode (a DOM resolver can't resolve the {@code !enum}/{@code !integer} instances a
     *     meta-schema declares), so it is taken as a {@link DataBindContext} and the bind-mode {@code
     *     ValueReaderFactoryResolver} is built from it here rather than accepted directly and possibly wrong.
     * @param source where {@link #loadMeta}/{@link #resolveLinked} fetch a not-yet-registered schema's
     *     source text from -- {@link TsonSchemaSource#registeredOnly()} by default, so nothing is fetched
     *     unless a caller opts in.
     */
    public TsonCompiledMetaRegistry(TsonSchemaRegistry schemaRegistry, DataBindContext context,
                                      TsonSchemaSource source) {
        this.schemaRegistry = schemaRegistry;
        this.resolver = ValueReaderFactoryRegistry.bind(context);
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
     * Compiles {@code linked} standalone against this registry's own object-binding resolver and wraps it as
     * a {@link TsonCompiledMetaSchema}, without registering or caching it. The one-off/bootstrap path: it is
     * how meta-kernel itself is first compiled -- the one deliberate circularity (§1.5), its own {@code
     * !!meta} naming itself, so there's no already-compiled governing meta to compile it against the
     * ordinary way; it doesn't need one, declaring its whole vocabulary itself. {@code linked} is expected
     * to come from {@code TsonSchemaLinker#linkBootstrap} for that case (the ordinary {@code link} would
     * need meta-kernel already registered to resolve its self-referential {@code !!meta}). Also builds a
     * governing meta from an already-resolved entry set that never went through this registry's own
     * fetch/resolve pipeline.
     */
    public TsonCompiledMetaSchema bootstrap(TsonLinkedSchema linked) {
        return new TsonCompiledMetaSchema(TsonSchemaCompiler.compile(linked, resolver), resolver);
    }

    /**
     * A registry with this library's three bundled schemas -- meta-kernel, meta, core -- already loaded,
     * plus {@code source} for any other, non-bundled URIs a caller later resolves. This is the ordinary
     * way to get a working registry; the plain constructors leave it empty (for a caller that populates
     * it itself, e.g. a test bootstrapping in isolation).
     */
    public static TsonCompiledMetaRegistry withStandardLibrary(DataBindContext context, TsonSchemaSource source) {
        TsonCompiledMetaRegistry registry = new TsonCompiledMetaRegistry(context, source);
        registry.loadStandardLibrary();
        return registry;
    }

    /**
     * Loads this library's three bundled schema documents into this registry in dependency order, so any
     * schema governed by (or importing) them resolves. Each is fetched straight from {@link
     * TsonBundledSchemas} (never the configured {@code source}) and registered; its own {@code
     * !!meta}/{@code !!import} targets are cache hits by the time they're needed (meta-kernel's own is
     * its self-referential bootstrap case, resolved ordinarily and registered explicitly since {@link
     * #loadMeta} never caches that identity -- see that method).
     */
    private void loadStandardLibrary() {
        registerBundled(TsonBundledSchemas.META_KERNEL_ID);
        registerBundled(TsonBundledSchemas.META_ID);
        registerBundled(TsonBundledSchemas.CORE_ID);
    }

    /**
     * Fetches one bundled schema's own source straight from {@link TsonBundledSchemas}, records its content
     * hash (so a later hash-pinned reference to it is verified), and resolves it against this registry (its
     * {@code !!meta}/{@code !!import} targets already loaded). A meta-layer schema (meta-kernel, meta.tn) is
     * compiled and cached here so it can go on to govern others; core.tn is not a meta (its {@code !!meta}
     * is meta.tn), so it is only resolved and registered -- its readers are compiled per mode in a {@link
     * TsonCompiledSchemaRegistry} when a user schema importing it is read, never needed standalone here.
     */
    private void registerBundled(String id) {
        String sourceText = TsonBundledSchemas.fetch(id);
        recordAndVerify(sourceText, id, TsonCanonicalIdentity.canonicalize(id));
        TsonSchemaParser parser = new TsonSchemaParser(sourceText);
        SchemaDocument document = parser.parseSchemaDocument();
        TsonSchema resolved = new SchemaResolver(this).resolveSchema(document, parser.schemaPositions());
        if (isMetaLayer(resolved)) {
            register(resolved, loadMeta(document.meta()));
        } else {
            schemaRegistry.register(TsonSchemaLinker.link(resolved, schemaRegistry));
        }
    }

    /**
     * Resolves {@code uri} to its compiled governing meta-schema, fetching/resolving/compiling on demand --
     * a cache hit ({@link #get}), meta-kernel's own hand-written bootstrap (never cached, since its {@code
     * !!meta} names itself, §1.5), or the generic path ({@link #resolveLinked} then compile+cache). A
     * target that resolves but isn't a meta-layer schema is rejected -- only such a schema may govern.
     * Deliberately not {@code synchronized}: it recurses into itself (via {@link #resolveLinked}), and
     * holding a lock across a whole fetch would serialize unrelated loads for no real benefit.
     */
    @Override
    public TsonCompiledMetaSchema loadMeta(String uri) {
        String identity = TsonCanonicalIdentity.canonicalize(uri);
        Optional<TsonCompiledMetaSchema> cached = get(uri);
        if (cached.isPresent()) {
            // Already compiled: verify *this* reference's own pin against the identity's content hash. A
            // conflicting pin (a different digest than the one this identity was verified against) errors
            // here rather than silently resolving to the cached instance (§10.2).
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
            return bootstrap(TsonSchemaLinker.linkBootstrap(metaKernel));
        }
        TsonLinkedSchema linked = resolveLinked(uri);
        if (!isMetaLayer(linked.schema())) {
            // An authoring error in whichever document named this as its !!meta, so it carries
            // TsonSchemaLinker's own wording for the same verdict -- see notAMetaSchema on why it is a
            // validation exception and not an IllegalStateException.
            throw TsonSchemaLinker.notAMetaSchema(uri, linked.schema().meta(), null);
        }
        // resolveLinked already resolved this document's own !!meta into the structure namespace, so this
        // loadMeta is a cache hit, not a second compile.
        return compileAndCache(linked, loadMeta(linked.schema().meta()));
    }

    /**
     * Resolves {@code uri} to its linked form -- fetching/resolving/linking/registering into the paired
     * {@link #schemaRegistry} on demand, but <b>not</b> compiling it or caching a compiled result. This is
     * what a per-mode {@link TsonCompiledSchemaRegistry} reads a user schema through, and what an {@code
     * !!import} target is resolved through: the schema is resolved once here (bind-anchored, so its own
     * {@code !enum}/{@code !integer} instances bind correctly), and each read registry compiles the
     * returned linked form itself, in its own mode -- so a non-meta schema is never compiled and cached in
     * this core just to be read or imported. Idempotent via {@link #schemaRegistry} (a second call is a
     * cache hit); this reference's own {@code ?sha256=} pin is verified each call (§10.2).
     *
     * <p>For ordinary schemas only -- not the self-referential meta-kernel bootstrap, which {@link
     * #loadMeta} owns (meta-kernel is never registered, so it has no linked form here to hand back).
     */
    @Override
    public TsonLinkedSchema resolveLinked(String uri) {
        return resolveLinked(uri, null);
    }

    /**
     * {@link #resolveLinked(String)} reporting every problem in the schema through {@code receiver} rather
     * than throwing at the first, with the same phase boundary {@code Tson.validateSchema} draws: every
     * declaration parses before a verdict, resolution runs only if the whole document parsed, and linking
     * only if resolution was clean.
     *
     * <p>Exists so a *read* of a data document can say what is wrong with the schema it names. Without it a
     * reader can only catch the first exception and flatten it, losing the other problems along with the
     * declaration each belongs to.
     *
     * <p><b>Returns {@code null} when anything was reported</b>, rather than a partly-resolved schema: the
     * placeholders resolution leaves behind are not a schema anyone should read against, and the caller has
     * the diagnostics that say why. A caller passing {@code null} for {@code receiver} gets the fail-fast
     * behaviour of the other overload, which never returns {@code null}.
     */
    public TsonLinkedSchema resolveLinked(String uri, TsonDiagnosticsReceiver receiver) {
        return resolveLinked(uri, TsonCanonicalIdentity.canonicalize(uri), receiver);
    }

    /**
     * {@link #resolveLinked(String, TsonDiagnosticsReceiver)} for a caller that has already canonicalized
     * {@code uri} -- {@code identity} must be exactly what {@code canonicalize(uri)} returns.
     *
     * <p>Canonicalizing is a {@code new URI(...)} parse, and a read reaches this three times for one
     * document (here, in the registry's own lookup, and in the compiled-schema cache above). One
     * canonicalization at the top of a read now serves all three. Not a shortcut past anything else: the
     * resolution and the {@code ?sha256=} pin verification below run exactly as they did.
     */
    public TsonLinkedSchema resolveLinked(String uri, String identity, TsonDiagnosticsReceiver receiver) {
        Optional<TsonLinkedSchema> cached = schemaRegistry.getByCanonicalIdentity(identity);
        if (cached.isPresent()) {
            verifyPin(uri, identity);
            return cached.get();
        }
        Set<String> chain = resolving.get();
        if (!chain.add(identity)) {
            throw importCycle(chain, identity);
        }
        try {
            return resolveUncached(uri, identity, receiver);
        } finally {
            chain.remove(identity);
        }
    }

    /**
     * §2.2.3's import cycle, named by the path that closes it. A schema is registered only once it has
     * linked, so the cycle cannot be found by a cache lookup -- being <em>in flight</em> is the whole
     * signal, and the chain of identities in flight is what makes the message actionable: any one of its
     * links is the edge to break.
     *
     * <p>An author error, not a library gap: the verdict does not change when this library improves. It
     * throws even where a {@link TsonDiagnosticsReceiver} is in play, on the same footing as an
     * {@code !!import} that will not load or an ineligible {@code !!meta} -- what fails is the namespace
     * itself, and carrying on would report every reference into the unresolvable half as a second problem.
     */
    private static TsonSchemaValidationException importCycle(Set<String> chain, String identity) {
        StringBuilder path = new StringBuilder();
        boolean fromCycleStart = false;
        for (String link : chain) {
            fromCycleStart |= link.equals(identity);
            if (fromCycleStart) {
                path.append(link).append(" -> ");
            }
        }
        return new TsonSchemaValidationException("'" + identity + "' is part of an import cycle ("
                + path + identity + ") -- a schema cannot depend, directly or transitively, on one that "
                + "depends on it, since neither can be resolved before the other ([TSON-DATA] §2.2.3). "
                + "Break the cycle by moving what both need into a third schema they each import");
    }

    /** {@link #resolveLinked(String, TsonDiagnosticsReceiver)}'s body, with the cycle guard already held. */
    private TsonLinkedSchema resolveUncached(String uri, String identity, TsonDiagnosticsReceiver receiver) {
        String sourceText = source.fetch(uri);
        // Record this identity's content hash (first resolution) and verify this reference's pin against
        // it -- §2.2.1's MUST-verify rule. A transitive pinned !!import/!!meta is verified likewise when
        // its own resolveLinked/load reaches here.
        recordAndVerify(sourceText, uri, identity);
        TsonSchemaParser parser = new TsonSchemaParser(sourceText);
        if (receiver == null) {
            SchemaDocument document = parser.parseSchemaDocument();
            crossCheckId(document, uri, identity);
            TsonSchema resolved = new SchemaResolver(this).resolveSchema(document, parser.schemaPositions());
            return schemaRegistry.registerIfAbsent(TsonSchemaLinker.link(resolved, schemaRegistry));
        }
        TsonDiagnosticsCollector problems = TsonDiagnosticsReceiver.collecting();
        Optional<SchemaDocument> parsed = parser.parseSchemaDocument(problems);
        if (parsed.isEmpty()) {
            problems.diagnostics().forEach(receiver::report);
            return null;
        }
        SchemaDocument document = parsed.get();
        crossCheckId(document, uri, identity);
        TsonSchema resolved = new SchemaResolver(this)
                .resolveSchema(document, parser.schemaPositions(), problems);
        if (problems.isEmpty()) {
            TsonLinkedSchema linked = TsonSchemaLinker.link(resolved, schemaRegistry, problems);
            if (problems.isEmpty()) {
                return schemaRegistry.registerIfAbsent(linked);
            }
        }
        problems.diagnostics().forEach(receiver::report);
        return null;
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
     * <p>Only a meta-layer schema (its own {@code !!meta} is meta-kernel) belongs here -- it is wrapped as
     * a {@link TsonCompiledMetaSchema} so it can go on to govern others. A non-meta schema is rejected: it
     * is compiled per mode in a {@link TsonCompiledSchemaRegistry} instead, never in this core. The result
     * is returned directly, so the immediate next schema in a governing chain doesn't have to {@link #get}
     * it again.
     *
     * @param governingMeta the already-compiled meta-schema {@code schema.meta()} names -- meta-
     *                       kernel's own case aside (see {@link TsonSchema#bootstrap()}),
     *                       always a previous call's own {@link #get} result
     */
    public synchronized TsonCompiledMetaSchema register(TsonSchema schema, TsonCompiledMetaSchema governingMeta) {
        TsonLinkedSchema registered = schemaRegistry.register(TsonSchemaLinker.link(schema, schemaRegistry));
        if (!isMetaLayer(registered.schema())) {
            throw new IllegalStateException("register is for meta-layer schemas only, but '"
                    + registered.schema().id() + "' is not one (its !!meta is not meta-kernel) -- a non-meta "
                    + "schema is compiled per mode in a TsonCompiledSchemaRegistry, not here");
        }
        return compileAndCache(registered, governingMeta);
    }

    /**
     * Compiles an already-linked, already-registered meta-layer schema against {@code governingMeta}, wraps
     * it as a {@link TsonCompiledMetaSchema}, and caches it by canonical identity. The compiled-cache half
     * of resolution, split from {@link #resolveLinked}'s register-only half. Callers guarantee the schema
     * is meta-layer ({@link #register}/{@link #loadMeta} both check first).
     *
     * <p><b>First one wins, rather than last.</b> {@link #loadMeta} checks the cache before doing the work,
     * so two threads reaching the same uncached meta together both compile it; storing both would leave two
     * equivalent instances for one identity, with whichever readers were built against the loser holding a
     * reference nothing else shares. Overwriting is not an error -- the two are equivalent -- but one
     * instance per identity is the property a cache is supposed to have. {@link #register}'s own path
     * cannot reach a duplicate at all: {@code schemaRegistry.register} refuses one first.
     */
    private synchronized TsonCompiledMetaSchema compileAndCache(TsonLinkedSchema registered,
                                                                TsonCompiledMetaSchema governingMeta) {
        String identity = TsonCanonicalIdentity.canonicalize(registered.schema().id());
        TsonCompiledMetaSchema existing = compiled.get(identity);
        if (existing != null) {
            return existing;
        }
        TsonCompiledMetaSchema result = new TsonCompiledMetaSchema(
                TsonSchemaCompiler.compile(registered, governingMeta), resolver);
        compiled.put(identity, result);
        return result;
    }

    /**
     * The compiled governing meta-schema registered under {@code id}, if any -- matched by canonical
     * identity ({@link TsonCanonicalIdentity#canonicalize}), so any spelling (pinned or plain) of a
     * registered schema's own {@code !!id} finds it. Only meta-layer schemas are ever stored here, so a
     * non-meta schema is never handed back where a governing meta is required.
     */
    public synchronized Optional<TsonCompiledMetaSchema> get(String id) {
        return Optional.ofNullable(compiled.get(TsonCanonicalIdentity.canonicalize(id)));
    }

    /**
     * A meta-layer schema is one whose own {@code !!meta} names meta-kernel (§9's meta layer: meta-kernel and
     * meta). Only such a schema compiles to a {@link TsonCompiledMetaSchema} and may govern others.
     */
    private static boolean isMetaLayer(TsonSchema schema) {
        return TsonCanonicalIdentity.sameIdentity(schema.meta(), TsonBundledSchemas.META_KERNEL_ID);
    }

    /**
     * A document obtained via a reference must own the identity it was fetched under: its embedded
     * {@code !!id} canonical identity MUST equal the reference's ([TSON-DATA] §2.2.1), so a source can't
     * return content under the wrong identity. A hash-pinned reference's target MUST carry an {@code
     * !!id} at all; a plain reference to an id-less development artifact is allowed here (registration
     * requires an id separately).
     *
     * <p>Both failures are {@link TsonSchemaValidationException}s, not library faults: the reference and the
     * document it names disagree, which is an authoring or publishing error whose verdict does not change
     * when this library improves. Same reasoning as {@link TsonSchemaLinker#notAMetaSchema} above, and what
     * lets {@code Tson.validateSchema} report them rather than rethrow.
     */
    private void crossCheckId(SchemaDocument document, String referenceUri, String identity) {
        if (document.id().isEmpty()) {
            if (TsonContentHash.declaredSha256(referenceUri).isPresent()) {
                throw new TsonSchemaValidationException("the hash-pinned reference \"" + referenceUri
                        + "\" resolved to a document with no !!id -- a hashed reference's target must carry one "
                        + "([TSON-DATA] §2.2.1)");
            }
            return;
        }
        String embedded = TsonCanonicalIdentity.canonicalize(document.id().get());
        if (!embedded.equals(identity)) {
            throw new TsonSchemaValidationException("identity mismatch: reference \"" + referenceUri
                    + "\" (identity \""
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
        String contentHash = TsonContentHash.sha256(sourceText.getBytes(StandardCharsets.UTF_8));
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
        TsonContentHash.declaredSha256(referenceUri).ifPresent(declared -> {
            if (!declared.equals(contentHash)) {
                throw new TsonContentHashMismatchException("content hash mismatch for \"" + referenceUri
                        + "\": the reference declares sha256=" + declared + " but the content for identity \""
                        + identity + "\" hashes to " + contentHash
                        + " -- refusing to use mismatched content ([TSON-DATA] §2.2.1, [TSON-SCHEMA] §10.2)");
            }
        });
    }
}
