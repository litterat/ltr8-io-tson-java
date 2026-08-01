package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.ContentHash;
import io.ltr8.tson.compiler.TsonCompiledMetaSchema;
import io.ltr8.tson.compiler.TsonCompiledSchemaLoader;
import io.ltr8.tson.compiler.TsonSchemaParser;
import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.compiler.config.TsonCompiledRegistry;
import io.ltr8.tson.compiler.ContentHashMismatchException;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchemaLinker;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.TsonSchemaRegistry;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The default {@link TsonCompiledSchemaLoader}: check the registry first, special-case meta-kernel's
 * own bootstrap next, and otherwise fetch, resolve, register, and compile a schema on demand.
 *
 * <ol>
 *   <li><b>Already compiled?</b> {@code registry.get(uri)} -- a plain cache hit, keyed the same
 *   (raw-{@code !!id}, not canonicalized) way {@link TsonCompiledRegistry} itself already is.</li>
 *   <li><b>Meta-kernel's own well-known identity?</b> Resolved via {@link
 *   MetaKernelBootstrapResolver#getMetaKernelSchema()} -- never through this loader's own generic
 *   path below, which would recurse forever: that path resolves a document via {@code
 *   SchemaResolver(this)}, and {@code SchemaResolver.resolveSchema} itself calls back into
 *   {@link #load} for the document's own {@code !!meta} target -- fine for any real schema, whose
 *   {@code !!meta} points at something *other* than itself, but meta-kernel's own {@code !!meta}
 *   names itself (Part 2 §1.5's "one deliberate circularity"). This check runs *before* that path is
 *   ever reached, so the loop never starts. Compared by exact string, not canonical identity
 *   ({@code CanonicalIdentity} stays internal-by-convention to {@code tson-schema}, same reasoning
 *   as {@link TsonCompiledRegistry}'s own raw-id keying) -- a real, narrower guarantee, not an
 *   oversight.
 *
 *   <p><b>A one-off, never registered/cached in the *shared* registry.</b> {@link
 *   MetaKernelBootstrapResolver#getMetaKernelSchema()}'s own output is linked via {@link
 *   TsonSchemaLinker#linkBootstrap} -- no registry involved at all, just that one static call --
 *   purely so {@code TsonSchemaLinker}'s own materialization/validation pass runs (synthesizing
 *   entries for argument-bearing {@code type_ref}s, e.g. {@code enum}'s own {@code members:
 *   set<token>}) before compiling ({@link TsonCompiledMetaSchema#bootstrap}, using this loader's own
 *   {@link TsonCompiledRegistry#resolver()} so it reads the same way anything else compiled here
 *   would). The *linked* result is never passed to {@link TsonCompiledRegistry#register} -- so every
 *   call for {@link TsonBundledSchemas#META_KERNEL_ID} that isn't already a cache hit re-bootstraps,
 *   re-links, and re-compiles from scratch, every time.
 *   The *permanent*, shared registry entry for meta-kernel comes from an explicit "load it and
 *   register it" step done once, elsewhere; until that step runs, this one-off bootstrap stands in
 *   for it, so nothing is ever left unable to resolve at all.
 *
 *   <p><b>One real, load-bearing consequence remains, even with linking in place.</b> Any *other*
 *   schema that {@code !!import}s meta-kernel (every real one does) will still fail its own
 *   registration with "{@code !!import '...' is not registered}" unless meta-kernel has *separately*
 *   been registered in the *shared* registry first: {@code TsonSchemaLinker}'s own import-merging
 *   (run inside {@code TsonSchemaRegistry#register}, a step distinct from {@code
 *   SchemaResolver}'s own resolution-time import-merging above) resolves an import via {@code
 *   TsonSchemaRegistry}'s own registered-only {@code TsonSchemaLoader}, which knows nothing about
 *   this loader or its one-off bootstrap case. In practice this means a caller resolving anything
 *   beyond meta-kernel itself still needs to register meta-kernel explicitly first -- resolved
 *   *ordinarily* via {@code SchemaResolver.resolveSchema} against a loader whose own bootstrap
 *   branch supplies the structure namespace (never the raw/one-off linked bootstrap form directly --
 *   {@code TsonSchemaRegistry#register} refuses any self-referential schema with {@code bootstrap()
 *   == true}, see its own Javadoc) -- before asking this loader for anything that transitively
 *   imports it. Linking the one-off bootstrap result fixes *reading* through it correctly, not the
 *   separate *import-merge* requirement.</li>
 *   <li><b>Otherwise</b>, fetch {@code uri}'s own source text via this loader's own {@link
 *   TsonSchemaSource} (default: {@link TsonSchemaSource#registeredOnly()}, so nothing is fetched from
 *   anywhere unless a caller opts in), parse it, resolve it via a fresh {@code SchemaResolver}
 *   constructed with *this same loader* (so that document's own {@code !!meta}/{@code !!import}
 *   targets resolve the same way, recursively, cache-then-bootstrap-then-fetch all the way down),
 *   then register and compile the result -- *this* result genuinely is cached, unlike meta-kernel's
 *   own bootstrap case above.</li>
 * </ol>
 */
public final class DefaultTsonCompiledSchemaLoader implements TsonCompiledSchemaLoader {

    private static final String META_KERNEL_IDENTITY =
            TsonSchemaRegistry.canonicalIdentity(TsonBundledSchemas.META_KERNEL_ID);

    private final TsonCompiledRegistry registry;
    private final TsonSchemaSource source;
    // Content hash per canonical identity, recorded when an identity is first resolved. Every
    // hash-pinned reference to an identity is verified against it -- so conflicting pins for one
    // identity error (at most one can match the content) and a plain reference resolves to the
    // verified instance ([TSON-SCHEMA] §10.2's per-identity verification).
    private final Map<String, String> contentHashes = new HashMap<>();

    /** No fetch capability -- only meta-kernel's own one-off bootstrap and whatever's already registered/compiled ever resolve. */
    public DefaultTsonCompiledSchemaLoader(TsonCompiledRegistry registry) {
        this(registry, TsonSchemaSource.registeredOnly());
    }

    public DefaultTsonCompiledSchemaLoader(TsonCompiledRegistry registry, TsonSchemaSource source) {
        this.registry = registry;
        this.source = source;
    }

    @Override
    public TsonCompiledMetaSchema load(String uri) {
        String identity = TsonSchemaRegistry.canonicalIdentity(uri);
        Optional<TsonCompiledMetaSchema> cached = registry.get(uri);
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
            // one-off result changes (58 entries, not 49), not its lifetime.
            recordAndVerify(TsonBundledSchemas.fetch(TsonBundledSchemas.META_KERNEL_ID), uri, identity);
            TsonLinkedSchema linked = TsonSchemaLinker.linkBootstrap(metaKernel);
            return TsonCompiledMetaSchema.bootstrap(linked, registry.resolver());
        }
        String sourceText = source.fetch(uri);
        // Record this identity's content hash (first resolution) and verify this reference's pin
        // against it -- §2.2.1's MUST-verify rule. A transitive pinned !!import/!!meta is verified
        // likewise when its own load(...) reaches here.
        recordAndVerify(sourceText, uri, identity);
        SchemaDocument document = new TsonSchemaParser(sourceText).parseSchemaDocument();
        SchemaResolver resolver = new SchemaResolver(this);
        TsonSchema resolved = resolver.resolveSchema(document);
        // resolveSchema already called load(document.meta()) internally to build its own structure
        // namespace, so this is a cache hit, not a second compile -- registry.register still needs
        // the governing TsonCompiledMetaSchema itself as its own second argument, which resolveSchema
        // has no way to hand back (it returns only the resolved TsonSchema).
        TsonCompiledMetaSchema governingMeta = load(document.meta());
        return registry.register(resolved, governingMeta);
    }

    /** Record {@code identity}'s content hash (first resolution only), then verify {@code uri}'s own pin. */
    private void recordAndVerify(String sourceText, String uri, String identity) {
        contentHashes.putIfAbsent(identity, ContentHash.sha256(sourceText.getBytes(StandardCharsets.UTF_8)));
        verifyPin(uri, identity);
    }

    /** Verify a reference's declared {@code ?sha256=} pin, if any, against the identity's known content hash. */
    private void verifyPin(String referenceUri, String identity) {
        ContentHash.declaredSha256(referenceUri).ifPresent(declared -> {
            String contentHash = contentHashes.get(identity);
            if (contentHash != null && !contentHash.equals(declared)) {
                throw new ContentHashMismatchException("content hash mismatch for \"" + referenceUri
                        + "\": the reference declares sha256=" + declared + " but the content for identity \""
                        + identity + "\" hashes to " + contentHash
                        + " -- refusing to use mismatched content ([TSON-DATA] §2.2.1, [TSON-SCHEMA] §10.2)");
            }
        });
    }
}
