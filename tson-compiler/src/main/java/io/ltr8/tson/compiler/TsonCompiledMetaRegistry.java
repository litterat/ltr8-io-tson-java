package io.ltr8.tson.compiler;

import io.ltr8.tson.base.SchemaFetchException;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.base.*;
import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.base.policy.IdentifierPolicy;
import io.ltr8.tson.base.policy.LimitsPolicy;
import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.compiler.reader.ValueReaderFactoryRegistry;
import io.ltr8.tson.compiler.reader.ValueReaderFactoryResolver;
import io.ltr8.tson.compiler.resolver.MetaKernelBootstrapResolver;
import io.ltr8.tson.compiler.resolver.SchemaResolver;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.TsonSchemaRegistry;
import io.ltr8.tson.base.SchemaValidationException;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The resolution core: the registry of compiled <b>vocabulary</b>, and the on-demand {@link
 * TsonCompiledSchemaLoader} that fills it. It pairs with a {@link TsonSchemaRegistry}'s store of resolved schemas,
 * adding the compiled half a resolution reads through: a schema whose constructors or annotation types another
 * schema's closure reaches is compiled here, in object-binding mode, once per identity ({@link #loadMeta}). In
 * practice that is meta-kernel and meta.tn, plus any library declaring constructors of its own. Every schema is
 * <i>resolved</i> here -- registered into the paired {@link TsonSchemaRegistry} via {@link #resolveLinked} -- and
 * compiled per mode for reading data in a {@link TsonCompiledSchemaRegistry}.
 *
 * <p><b>As a loader</b> it resolves a schema on demand from its URI, so a resolver reaching an {@code !!import}
 * target needn't have it pre-registered -- {@link #loadMeta} for a schema's compiled vocabulary, {@link
 * #resolveLinked} for its resolved form. Both, in order: a cache hit; meta-kernel's own well-known identity, answered
 * by its bootstrap until the kernel is registered (the kernel is pre-loaded and never resolved from a document,
 * [TSON-SCHEMA] §3.4); otherwise fetch via the configured {@link SchemaSource}, parse, resolve via a fresh {@code
 * SchemaResolver} bound to this same loader (so the document's own {@code !!import}s resolve recursively, all the
 * way down). A fetched import target that is not a schema document -- its root not tagged {@code !schema} -- fails
 * to parse as one, which is how importing a data document is refused.
 * Content hashes are recorded and verified per identity along the way ([TSON-DATA] §2.2.1, [TSON-SCHEMA]
 * §10.2).
 *
 * <p><b>{@link #withStandardLibrary} is the ordinary entry point</b>: it builds a registry with the five
 * bundled schemas (meta-kernel, meta, core, net, policy) already loaded -- fetched straight from {@link
 * TsonBundledSchemas}, so it works whatever the configured source. The plain constructors leave the
 * registry empty, for a caller that populates it itself. Any schema governed by (or importing) the
 * bundled schemas then reuses what's already in {@link #get} rather than recompiling its chain.
 *
 * <p><b>Keyed by canonical identity</b> ({@link CanonicalIdentity#canonicalize}, scheme and query
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
            CanonicalIdentity.canonicalize(TsonBundledSchemas.META_KERNEL_ID);

    private final TsonSchemaRegistry schemaRegistry;
    private final ValueReaderFactoryResolver resolver;
    /** What {@link #resolver} binds with, and so what every namespace value the resolver reads is bound under. */
    private final DataBindContext context;
    private final SchemaSource source;
    private final Map<String, TsonCompiledMetaSchema> compiled = new LinkedHashMap<>();
    private final Map<String, TsonCompiledSchema> trees = new ConcurrentHashMap<>();
    /**
     * Meta-kernel's bootstrap stand-in, compiled on the first lookup of its identity and reused until the
     * ordinarily resolved kernel is registered. The bootstrap is a function of the bundled bytes and this
     * registry's resolver alone, so two threads racing here build equal values and either may win.
     */
    private volatile TsonCompiledMetaSchema bootstrapped;
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
     * Recorded for an identity whose document cannot be content-addressed at all -- no line terminator
     * after its id line, so §2.2.1's hash input has no boundary. Never a hash (which is 64 hex digits), so
     * no pin can match it, which is the point: a pinned reference to such a document is refused rather
     * than left silently unverified, and the document itself still loads for every reference that pins
     * nothing.
     */
    private static final String UNADDRESSABLE = "";

    /**
     * [TSON-DATA] §8.2's identifier policy for a schema's names, applied by {@link TsonSchemaLinker} wherever a
     * schema names something and over every scope those names form, and by the resolver to the identifier-typed
     * values a constructor payload carries. Held here because this registry is the
     * one object every resolve and every read already passes through, so a policy set on it reaches both
     * without a second channel.
     *
     * <p>The default is Highly Restrictive over a whole name -- the strictest of §5.2's practically
     * deployable levels, and one it names, so two implementations agree on the default without reading
     * this project's own documents. A deployment that finds it too strict reaches for the *unit* before the
     * level: {@code perSegment()} still refuses every within-word homograph.
     */
    private IdentifierPolicy identifierPolicy = IdentifierPolicy.defaults();

    /**
     * [TSON-SCHEMA] §11.5's limits a schema resolved here is resolved under -- the wait depth is the one a resolution
     * counts ({@code SchemaResolver}) -- the spec's defaults unless {@link #withStandardLibrary} was given others.
     */
    private LimitsPolicy limits = LimitsPolicy.defaults();

    /**
     * The identities this thread is part-way through resolving, outermost first -- [TSON-DATA] §2.2.3's
     * import-cycle guard ({@link #loadMeta} reaches every link through {@link #resolveLinked}).
     *
     * <p><b>Why a guard is needed at all:</b> an {@code !!import} is resolved through this
     * registry, and a schema is registered only once it is fully linked -- so while {@code a.tn} is resolving it is in
     * no cache, and {@code b.tn} importing it back re-enters {@link #resolveLinked} for an identity already in flight.
     * Unguarded that is unbounded recursion ending in a {@link StackOverflowError}: an {@link Error} from ordinary
     * author input, which no diagnostic ever sees and which the exception policy cannot classify.
     *
     * <p><b>Per thread, not per registry.</b> Concurrent resolution of one identity by two threads is
     * documented as safe here and settles at the caches; a registry-wide set would make the second thread's
     * ordinary in-flight entry look like a cycle to the first. Recursion through {@code !!import} is
     * strictly within one thread, so per-thread is both correct and exactly the scope of the
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
        this(schemaRegistry, context, SchemaSource.registeredOnly());
    }

    /** A fresh, empty {@link TsonSchemaRegistry} of its own, with a fetch {@code source}. */
    public TsonCompiledMetaRegistry(DataBindContext context, SchemaSource source) {
        this(new TsonSchemaRegistry(), context, source);
    }

    /**
     * @param context the object-binding context this registry compiles vocabulary with -- always
     *     object-binding mode (a DOM resolver can't resolve the {@code !enum}/{@code !integer} instances a
     *     schema declares), so it is taken as a {@link DataBindContext} and the bind-mode {@code
     *     ValueReaderFactoryResolver} is built from it here rather than accepted directly and possibly wrong.
     * @param source where {@link #loadMeta}/{@link #resolveLinked} fetch a not-yet-registered schema's
     *     source text from -- {@link SchemaSource#registeredOnly()} by default, so nothing is fetched
     *     unless a caller opts in.
     */
    public TsonCompiledMetaRegistry(TsonSchemaRegistry schemaRegistry, DataBindContext context,
                                      SchemaSource source) {
        this.schemaRegistry = schemaRegistry;
        this.resolver = ValueReaderFactoryRegistry.bind(context);
        this.context = context;
        this.source = source;
    }

    /**
     * The paired {@link TsonSchemaRegistry} -- a caller resolving a declaration against one of the schemas
     * registered here (an {@code !!import} target, ...) reads its resolved entries from there, same
     * as always; this registry only adds the compiled half.
     */
    public TsonSchemaRegistry schemaRegistry() {
        return schemaRegistry;
    }

    /**
     * Compiles {@code linked} against this registry's own object-binding resolver as vocabulary ({@link
     * TsonCompiledMetaSchema#compile}), without registering or caching it. The one-off/bootstrap path: it is how
     * meta-kernel itself is first compiled, pre-loaded ([TSON-SCHEMA] §3.4). {@code linked} is expected to come from
     * {@code TsonSchemaLinker#linkBootstrap} for that case. Also compiles an already-resolved entry set that never went
     * through this registry's own fetch/resolve pipeline.
     */
    public TsonCompiledMetaSchema bootstrap(TsonLinkedSchema linked) {
        return TsonCompiledMetaSchema.compile(linked, resolver);
    }

    /**
     * A registry with this library's five bundled schemas -- meta-kernel, meta, core, net, policy -- already loaded,
     * plus {@code source} for any other, non-bundled URIs a caller later resolves. This is the ordinary
     * way to get a working registry; the plain constructors leave it empty (for a caller that populates
     * it itself, e.g. a test bootstrapping in isolation).
     */
    public static TsonCompiledMetaRegistry withStandardLibrary(DataBindContext context, SchemaSource source) {
        return withStandardLibrary(context, source, IdentifierPolicy.defaults());
    }

    /** The same, with {@link #identifierPolicy} chosen rather than defaulted. */
    public static TsonCompiledMetaRegistry withStandardLibrary(DataBindContext context, SchemaSource source,
                                                               IdentifierPolicy identifierPolicy) {
        return withStandardLibrary(context, source, identifierPolicy, LimitsPolicy.defaults());
    }

    /** The same, with {@link #limits} chosen rather than defaulted as well. */
    public static TsonCompiledMetaRegistry withStandardLibrary(DataBindContext context, SchemaSource source,
                                                               IdentifierPolicy identifierPolicy,
                                                               LimitsPolicy limits) {
        TsonCompiledMetaRegistry registry = new TsonCompiledMetaRegistry(context, source);
        registry.identifierPolicy = Objects.requireNonNull(identifierPolicy, "identifierPolicy");
        registry.limits = Objects.requireNonNull(limits, "limits");
        registry.loadStandardLibrary();
        return registry;
    }

    /** The identifier policy this registry applies to a schema's names -- see {@link #identifierPolicy}. */
    @Override
    public IdentifierPolicy identifierPolicy() {
        return identifierPolicy;
    }

    /** The limits this registry resolves schemas under -- see {@link #limits}. */
    @Override
    public LimitsPolicy limits() {
        return limits;
    }

    @Override
    public TsonCompiledSchema compileReady(TsonSchema ready, Set<String> pending, boolean tree) {
        TsonLinkedSchema linked = TsonSchemaLinker.linkReady(ready, schemaRegistry, pending);
        return tree ? TsonSchemaCompiler.compileReady(linked, ValueReaderFactoryRegistry.tree(), pending)
                : TsonCompiledMetaSchema.compileReady(linked, resolver, pending);
    }

    /**
     * Loads this library's five bundled schema documents into this registry in dependency order, so any
     * schema importing them resolves. Each is fetched straight from {@link TsonBundledSchemas} (never the
     * configured {@code source}) and registered; its own {@code !!import} targets are cache hits
     * by the time they're needed (meta-kernel is resolved ordinarily against its pre-loaded stand-in and registered
     * explicitly -- see {@link #loadMeta}).
     */
    private void loadStandardLibrary() {
        registerBundled(TsonBundledSchemas.META_KERNEL_ID);
        registerBundled(TsonBundledSchemas.META_ID);
        registerBundled(TsonBundledSchemas.CORE_ID);
        registerBundled(TsonBundledSchemas.NET_ID);
        registerBundled(TsonBundledSchemas.POLICY_ID);
    }

    /**
     * Fetches one bundled schema's own source straight from {@link TsonBundledSchemas}, records its content
     * hash (so a later hash-pinned reference to it is verified), resolves it against this registry (its
     * {@code !!import} targets already loaded) and registers it. Its compiled vocabulary is built on
     * first use ({@link #loadMeta}), so a bundled schema no resolution reads through is never compiled here.
     */
    private void registerBundled(String id) {
        String sourceText = TsonBundledSchemas.fetch(id);
        recordAndVerify(sourceText, id, CanonicalIdentity.canonicalize(id));
        TsonSchemaParser parser = new TsonSchemaParser(sourceText);
        SchemaDocument document = parser.parseSchemaDocument();
        // The library's own schemas are resolved under the default limits, as they are linked under the default
        // identifier policy: a deployment's configuration should not be able to break the standard library.
        TsonSchema resolved = new SchemaResolver(this, LimitsPolicy.defaults())
                .resolveSchema(document, parser.schemaPositions());
        schemaRegistry.register(TsonSchemaLinker.link(resolved, schemaRegistry, IdentifierPolicy.defaults(), context));
    }

    /**
     * Resolves {@code uri} to its compiled vocabulary, fetching/resolving/compiling on demand -- a cache hit
     * ({@link #get}), meta-kernel's pre-loaded bootstrap while the kernel is not yet registered (built once per
     * registry, the kernel being pre-loaded, [TSON-SCHEMA] §3.4), or the generic path ({@link #resolveLinked} then
     * compile+cache). Any schema compiles: one namespace per schema puts every schema's constructors in reach of
     * the schemas importing it.
     * Deliberately not {@code synchronized}: it recurses into itself (via {@link #resolveLinked}), and
     * holding a lock across a whole fetch would serialize unrelated loads for no real benefit.
     */
    @Override
    public TsonCompiledMetaSchema loadMeta(String uri) {
        String identity = CanonicalIdentity.canonicalize(uri);
        Optional<TsonCompiledMetaSchema> cached = get(uri);
        if (cached.isPresent()) {
            // Already compiled: verify *this* reference's own pin against the identity's content hash. A
            // conflicting pin (a different digest than the one this identity was verified against) errors
            // here rather than silently resolving to the cached instance (§10.2).
            verifyPin(uri, identity);
            return cached.get();
        }
        if (META_KERNEL_IDENTITY.equals(identity) && schemaRegistry.getByCanonicalIdentity(identity).isEmpty()) {
            // The bootstrap's own output, linked and compiled but never registered -- TsonSchemaRegistry
            // refuses a bootstrap schema outright. The permanent, shared registry entry for meta-kernel comes
            // from an explicit "load it and register it" step done once elsewhere, resolving the kernel
            // ordinarily against this one; until then this stand-in, built once per registry, answers every
            // lookup, so nothing is ever left unable to resolve.
            recordAndVerify(TsonBundledSchemas.fetch(TsonBundledSchemas.META_KERNEL_ID), uri, identity);
            TsonCompiledMetaSchema standIn = bootstrapped;
            if (standIn == null) {
                standIn = bootstrap(TsonSchemaLinker.linkBootstrap(MetaKernelBootstrapResolver.getMetaKernelSchema()));
                bootstrapped = standIn;
            }
            return standIn;
        }
        return compileAndCache(resolveLinked(uri));
    }

    /**
     * The compiled vocabulary of the registered schema whose canonical identity is {@code identity} -- the schema
     * an imported entry records as its origin, which is how a resolution reaches the readers of a head it applies.
     * Compiled on first use and cached; meta-kernel's identity is answered by its pre-loaded stand-in until the
     * kernel is registered.
     *
     * @throws IllegalStateException if no schema is registered under {@code identity}: an origin names a schema the
     *                               import that carried the entry was linked against
     */
    @Override
    public TsonCompiledMetaSchema vocabularyOf(String identity) {
        TsonCompiledMetaSchema cached;
        synchronized (this) {
            cached = compiled.get(identity);
        }
        if (cached != null) {
            return cached;
        }
        Optional<TsonLinkedSchema> registered = schemaRegistry.getByCanonicalIdentity(identity);
        if (registered.isPresent()) {
            return compileAndCache(registered.get());
        }
        if (META_KERNEL_IDENTITY.equals(identity)) {
            return loadMeta(TsonBundledSchemas.META_KERNEL_ID);
        }
        throw new IllegalStateException("'" + identity + "' is named as the schema that declared an imported entry, "
                + "and no schema is registered under it");
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
     * <p>For ordinary schemas only -- not the meta-kernel's pre-loaded bootstrap, which {@link
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
    public TsonLinkedSchema resolveLinked(String uri, DiagnosticsReceiver receiver) {
        return resolveLinked(uri, CanonicalIdentity.canonicalize(uri), receiver);
    }

    /**
     * {@link #resolveLinked(String, DiagnosticsReceiver)} for a caller that has already canonicalized
     * {@code uri} -- {@code identity} must be exactly what {@code canonicalize(uri)} returns.
     *
     * <p>Canonicalizing is a {@code new URI(...)} parse, and a read reaches this three times for one
     * document (here, in the registry's own lookup, and in the compiled-schema cache above). One
     * canonicalization at the top of a read now serves all three. Not a shortcut past anything else: the
     * resolution and the {@code ?sha256=} pin verification below run exactly as they did.
     */
    public TsonLinkedSchema resolveLinked(String uri, String identity, DiagnosticsReceiver receiver) {
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
     * throws even where a {@link DiagnosticsReceiver} is in play, on the same footing as an
     * {@code !!import} that will not load -- what fails is the namespace
     * itself, and carrying on would report every reference into the unresolvable half as a second problem.
     */
    private static SchemaValidationException importCycle(Set<String> chain, String identity) {
        StringBuilder path = new StringBuilder();
        boolean fromCycleStart = false;
        for (String link : chain) {
            fromCycleStart |= link.equals(identity);
            if (fromCycleStart) {
                path.append(link).append(" -> ");
            }
        }
        return new SchemaValidationException("'" + identity + "' is part of an import cycle ("
                + path + identity + ") -- a schema cannot depend, directly or transitively, on one that "
                + "depends on it, since neither can be resolved before the other ([TSON-DATA] §2.2.3). "
                + "Break the cycle by moving what both need into a third schema they each import");
    }

    /**
     * The source's text for {@code uri}, with the one thing the contract cannot express in a type checked
     * here: a {@code null} return.
     *
     * <p><b>{@link SchemaSource} permits exactly one way to say "cannot supply this"</b>, a {@link
     * SchemaFetchException}, and that is what lets {@code SchemaFailure} tell an unavailable schema from
     * a fault by type. A {@code null} says it a second way that carries no {@link
     * SchemaFetchException.Reason} at all, so the deployment refusing a reference and the host that did
     * not answer arrive indistinguishable -- and unguarded it arrives instead as a {@code
     * NullPointerException} several frames further in, nowhere near the source that caused it.
     *
     * <p><b>An {@link IllegalStateException}, so it stays a fault.</b> A source is the caller's own code and
     * a broken one is a bug in the deployment, not a verdict on the document that happened to name the
     * schema: {@code SchemaFailure}'s default rethrows it rather than reporting the document invalid, which
     * is right, and it must not become a fetch failure -- treating {@code null} as a miss would
     * make the wrong spelling work and hide every later one.
     *
     * <p>The trap this catches is {@code schemaSource(schemas::get)}, so the message names {@link
     * SchemaSource#ofMap} -- the thing that call meant.
     *
     * <p>Meta-kernel is never asked of the source: it is the revision this library was built for, read from {@link
     * TsonBundledSchemas}.
     */
    private String fetch(String uri) {
        if (META_KERNEL_IDENTITY.equals(CanonicalIdentity.canonicalize(uri))) {
            // The kernel is the one the software was built for, whatever the source would answer: it is read from
            // the library's own bytes, so a schema importing it resolves
            // under any source, as the pre-loaded stand-in does.
            return TsonBundledSchemas.fetch(TsonBundledSchemas.META_KERNEL_ID);
        }
        String sourceText = source.fetch(uri);
        if (sourceText == null) {
            throw new IllegalStateException("the SchemaSource " + source.getClass().getName()
                    + " returned null for '" + uri + "'. A source signals \"cannot supply this\" by throwing "
                    + "SchemaFetchException and nothing else, so that an unavailable schema can be told "
                    + "from a fault by type and carries a Reason saying which; null is neither. If this "
                    + "source is a map lookup, SchemaSource.ofMap(Map) is that lookup done to contract");
        }
        return sourceText;
    }

    /** {@link #resolveLinked(String, DiagnosticsReceiver)}'s body, with the cycle guard already held. */
    private TsonLinkedSchema resolveUncached(String uri, String identity, DiagnosticsReceiver receiver) {
        String sourceText = fetch(uri);
        // Record this identity's content hash (first resolution) and verify this reference's pin against
        // it -- §2.2.1's MUST-verify rule. A transitive pinned !!import is verified likewise when
        // its own resolveLinked/load reaches here.
        recordAndVerify(sourceText, uri, identity);
        TsonSchemaParser parser = new TsonSchemaParser(sourceText);
        if (receiver == null) {
            SchemaDocument document = parser.parseSchemaDocument();
            crossCheckId(document, uri, identity);
            TsonSchema resolved = new SchemaResolver(this).resolveSchema(document, parser.schemaPositions());
            return schemaRegistry.registerIfAbsent(link(resolved));
        }
        DiagnosticsCollector problems = DiagnosticsReceiver.collecting();
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
            TsonLinkedSchema linked = link(resolved, problems);
            if (problems.isEmpty()) {
                return schemaRegistry.registerIfAbsent(linked);
            }
        }
        problems.diagnostics().forEach(receiver::report);
        return null;
    }

    /**
     * Links {@code resolved}, a schema this registry's resolver read, against the schemas registered here, under
     * {@link #identifierPolicy} and the bind context its namespace values were bound under, which a value named
     * through a record value's field is read by ({@link TsonSchemaLinker#link(TsonSchema,
     * io.ltr8.tson.schema.TsonSchemaLoader, IdentifierPolicy, DataBindContext)}). Throws at the first failing entry.
     */
    public TsonLinkedSchema link(TsonSchema resolved) {
        return TsonSchemaLinker.link(resolved, schemaRegistry, identifierPolicy, context);
    }

    /** {@link #link(TsonSchema)} reporting each failing entry through {@code receiver} instead of throwing. */
    public TsonLinkedSchema link(TsonSchema resolved, DiagnosticsReceiver receiver) {
        return TsonSchemaLinker.link(resolved, schemaRegistry, receiver, identifierPolicy, context);
    }

    /**
     * Registers an already-linked schema a caller resolved from text it holds, recording that text's
     * content hash for the schema's own identity -- {@link #resolveUncached}'s bookkeeping for a document
     * that never went through a {@link SchemaSource}, and the document's own {@code !!id} pin verified
     * against its bytes on the way past (an id line may carry the document's own hash, §2.2.1).
     *
     * <p><b>One call rather than two, because [TSON-SCHEMA] §10.2 verifies per identity and not per
     * route.</b> A hash recorded only where a schema was fetched leaves {@link #verifyPin} with nothing to
     * compare a later pinned reference against, and its silence in that case is indistinguishable from a
     * verified pin: a wrong {@code ?sha256=} on a schema this process registered itself would validate
     * clean. Pairing the two here is what keeps the registering caller from having to remember the second
     * half.
     */
    public TsonLinkedSchema register(TsonLinkedSchema linked, String sourceText) {
        String id = linked.schema().id();
        recordAndVerify(sourceText, id, CanonicalIdentity.canonicalize(id));
        return schemaRegistry.register(linked);
    }

    /**
     * Links {@code schema} (via {@link TsonSchemaLinker#link}, using the paired {@link
     * #schemaRegistry} itself as the lookup source for {@code !!import} targets) and
     * registers the result (via {@link TsonSchemaRegistry#register}, so the usual {@code
     * !!import}-merging/reference-validation rules all apply exactly as they would calling that
     * directly), then compiles the *registered* result as vocabulary (never the raw input -- a compiled
     * reader needs linking already done) and stores it keyed by {@code schema}'s own {@code !!id}. The
     * result is returned directly, so a caller building a chain by hand doesn't have to {@link #get} it again.
     */
    public synchronized TsonCompiledMetaSchema register(TsonSchema schema) {
        TsonLinkedSchema registered = schemaRegistry.register(TsonSchemaLinker.link(schema, schemaRegistry,
                IdentifierPolicy.defaults(), context));
        return compileAndCache(registered);
    }

    /**
     * Compiles an already-linked, already-registered schema as vocabulary ({@link TsonCompiledMetaSchema#compile})
     * and caches it by canonical identity. The compiled-cache half of resolution, split from {@link
     * #resolveLinked}'s register-only half.
     *
     * <p><b>First one wins, rather than last.</b> {@link #loadMeta} checks the cache before doing the work,
     * so two threads reaching the same uncached meta together both compile it; storing both would leave two
     * equivalent instances for one identity, with whichever readers were built against the loser holding a
     * reference nothing else shares. Overwriting is not an error -- the two are equivalent -- but one
     * instance per identity is the property a cache is supposed to have. {@link #register}'s own path
     * cannot reach a duplicate at all: {@code schemaRegistry.register} refuses one first.
     */
    @Override
    public TsonCompiledSchema treeVocabularyOf(String identity) {
        TsonCompiledSchema cached = trees.get(identity);
        if (cached != null) {
            return cached;
        }
        TsonLinkedSchema registered = schemaRegistry.getByCanonicalIdentity(identity).orElseThrow(
                () -> new IllegalStateException("'" + identity + "' is named as the schema that declared a value "
                        + "entry's type, and no schema is registered under it"));
        return trees.computeIfAbsent(identity,
                id -> TsonSchemaCompiler.compile(registered, ValueReaderFactoryRegistry.tree()));
    }

    private synchronized TsonCompiledMetaSchema compileAndCache(TsonLinkedSchema registered) {
        String identity = CanonicalIdentity.canonicalize(registered.schema().id());
        TsonCompiledMetaSchema existing = compiled.get(identity);
        if (existing != null) {
            return existing;
        }
        TsonCompiledMetaSchema result = TsonCompiledMetaSchema.compile(registered, resolver);
        compiled.put(identity, result);
        return result;
    }

    /**
     * The compiled vocabulary cached under {@code id}, if any -- matched by canonical identity ({@link
     * CanonicalIdentity#canonicalize}), so any spelling (pinned or plain) of a registered schema's own {@code !!id}
     * finds it. Present once {@link #loadMeta} or {@link #register} compiled it.
     */
    public synchronized Optional<TsonCompiledMetaSchema> get(String id) {
        return Optional.ofNullable(compiled.get(CanonicalIdentity.canonicalize(id)));
    }

    /**
     * A document obtained via a reference must own the identity it was fetched under: its embedded
     * {@code !!id} canonical identity MUST equal the reference's ([TSON-DATA] §2.2.1), so a source can't
     * return content under the wrong identity. A hash-pinned reference's target MUST carry an {@code
     * !!id} at all; a plain reference to an id-less development artifact is allowed here (registration
     * requires an id separately).
     *
     * <p>Both failures are {@link SchemaValidationException}s, not library faults: the reference and the
     * document it names disagree, which is an authoring or publishing error whose verdict does not change
     * when this library improves, which is what lets {@code Tson.validateSchema} report them rather than rethrow.
     */
    private void crossCheckId(SchemaDocument document, String referenceUri, String identity) {
        if (document.id().isEmpty()) {
            if (TsonContentHash.declaredSha256(referenceUri).isPresent()) {
                throw new SchemaValidationException("the hash-pinned reference \"" + referenceUri
                        + "\" resolved to a document with no !!id -- a hashed reference's target must carry one "
                        + "([TSON-DATA] §2.2.1)");
            }
            return;
        }
        String embedded = CanonicalIdentity.canonicalize(document.id().get());
        if (!embedded.equals(identity)) {
            throw new SchemaValidationException("identity mismatch: reference \"" + referenceUri
                    + "\" (identity \""
                    + identity + "\") resolved to a document whose own !!id is \"" + document.id().get()
                    + "\" (identity \"" + embedded + "\") -- refusing content obtained under the wrong identity "
                    + "([TSON-DATA] §2.2.1)");
        }
    }

    /**
     * Verify {@code uri}'s own pin against the content, then record the content hash for the
     * identity -- verification *first*, so a rejected fetch records nothing and cannot poison the
     * identity's cache entry for a later, valid one (§10.2 caching semantics). {@code putIfAbsent}
     * keeps the first-resolved (known-good) hash immutable thereafter.
     *
     * <p><b>A document with no line terminator after its id line records {@link #UNADDRESSABLE}</b> rather
     * than failing here: §2.2.1 requires that terminator of a <i>content-addressed</i> document, so its
     * absence refuses the pin and never the document -- which is what lets a single-line schema, which no
     * reference can pin, still load.
     */
    private void recordAndVerify(String sourceText, String uri, String identity) {
        String contentHash = TsonContentHash
                .sha256IfAddressable(sourceText.getBytes(StandardCharsets.UTF_8)).orElse(UNADDRESSABLE);
        // A pre-loaded bundled schema ships with a digest the library holds (§10.2): the shipped bytes
        // MUST match it -- the authoritative digest a pinned reference to it is checked against, and an
        // integrity check that the packaged resource is the one the library was built for.
        TsonBundledSchemas.declaredSha256(uri).ifPresent(held -> {
            if (!held.equals(contentHash)) {
                throw new IllegalStateException("bundled schema \"" + identity + "\" content hashes to "
                        + (contentHash.isEmpty() ? "nothing (no line terminator after its !!id line)" : contentHash)
                        + " but the library holds digest " + held
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
            if (contentHash.equals(UNADDRESSABLE)) {
                throw new ContentHashMismatchException("the hash-pinned reference \"" + referenceUri
                        + "\" names a document with no line terminator after its !!id line, so it has no "
                        + "content hash to verify against -- the target of a hashed reference must carry an "
                        + "id line ([TSON-DATA] §2.2.1)");
            }
            if (!declared.equals(contentHash)) {
                throw new ContentHashMismatchException("content hash mismatch for \"" + referenceUri
                        + "\": the reference declares sha256=" + declared + " but the content for identity \""
                        + identity + "\" hashes to " + contentHash
                        + " -- refusing to use mismatched content ([TSON-DATA] §2.2.1, [TSON-SCHEMA] §10.2)");
            }
        });
    }
}
