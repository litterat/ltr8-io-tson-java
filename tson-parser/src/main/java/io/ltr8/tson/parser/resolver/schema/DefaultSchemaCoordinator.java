package io.ltr8.tson.parser.resolver.schema;

import io.ltr8.tson.parser.TsonSchemaParser;
import io.ltr8.tson.parser.ast.schema.SchemaDocument;
import io.ltr8.tson.parser.resolver.schema.compiled.TsonCompiledRegistry;
import io.ltr8.tson.parser.resolver.schema.compiled.TsonCompiledSchema;
import io.ltr8.tson.parser.resolver.schema.compiled.TsonSchemaCompiler;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchemaLinker;
import io.ltr8.tson.schema.TsonSchema;

import java.util.Optional;

/**
 * The default {@link SchemaCoordinator}: check the registry first, special-case meta-kernel's own
 * bootstrap next, and otherwise fetch, resolve, register, and compile a schema on demand.
 *
 * <ol>
 *   <li><b>Already compiled?</b> {@code registry.get(uri)} -- a plain cache hit, keyed the same
 *   (raw-{@code !!id}, not canonicalized) way {@link TsonCompiledRegistry} itself already is.</li>
 *   <li><b>Meta-kernel's own well-known identity?</b> Resolved via {@link
 *   MetaKernelBootstrapResolver#getMetaKernelSchema()} -- never through this coordinator's own generic path
 *   below, which would recurse forever: that
 *   path resolves a document via {@code TsonSchemaResolver(this)}, and {@code TsonSchemaResolver.resolveAll}
 *   itself calls back into {@link #resolve} for the document's own {@code !!meta} target -- fine for
 *   any real schema, whose {@code !!meta} points at something *other* than itself, but meta-kernel's
 *   own {@code !!meta} names itself (Part 2 §1.5's "one deliberate circularity"). This check runs
 *   *before* that path is ever reached, so the loop never starts. Compared by exact string, not
 *   canonical identity ({@code CanonicalIdentity} stays internal-by-convention to {@code
 *   tson-schema}, same reasoning as {@link TsonCompiledRegistry}'s own raw-id keying) -- a real,
 *   narrower guarantee, not an oversight.
 *
 *   <p><b>Deliberately a one-off, never registered/cached in the *shared* registry</b> (on the
 *   user's own explicit direction): {@link MetaKernelBootstrapResolver#getMetaKernelSchema()}'s own
 *   output is linked via {@link TsonSchemaLinker#linkBootstrap} -- no registry involved at all, just
 *   that one static call -- purely so {@code TsonSchemaLinker}'s own materialization/validation pass
 *   runs (synthesizing entries for argument-bearing {@code type_ref}s, e.g. {@code enum}'s own
 *   {@code members: set<token>}) before compiling ({@code TsonSchemaCompiler.compile}, using this
 *   coordinator's own {@link io.ltr8.tson.parser.resolver.schema.compiled.TsonParserFactoryRegistry}
 *   so it reads the same way anything else compiled here would). The *linked* result is never passed
 *   to {@link TsonCompiledRegistry#register} -- so every call for {@link
 *   BundledSchemaSource#META_KERNEL_ID} that isn't already a cache hit re-bootstraps, re-links, and
 *   re-compiles from scratch, every time -- only the *quality* of the one-off result changed (58
 *   entries, matching a genuinely registered meta-kernel, not the raw 49), never its lifetime.
 *   Deliberately, not an oversight: the *permanent*, shared registry entry for meta-kernel is meant
 *   to come from an explicit, deliberate "load it (from disk, or eventually a real {@link
 *   TsonSchemaSource}) and register it" step done once, elsewhere -- not implicitly, silently, the
 *   first time anything happens to ask for meta-kernel's own {@code !!meta}/{@code !!import} target.
 *   Until that explicit step exists and runs, this one-off bootstrap is what stands in for it, every
 *   time, so nothing is ever left unable to resolve at all.
 *
 *   <p><b>One real, load-bearing consequence remains, even with linking fixed.</b> Any *other* schema
 *   that {@code !!import}s meta-kernel (every real one does) will still fail its own registration
 *   with "{@code !!import '...' is not registered}" unless meta-kernel has *separately* been
 *   registered in the *shared* registry first: {@code TsonSchemaLinker}'s own import-merging (run
 *   inside {@code TsonSchemaRegistry#register}, a step distinct from {@code TsonSchemaResolver}'s own
 *   resolution-time import-merging above) resolves an import via {@code TsonSchemaRegistry}'s own
 *   registered-only {@code TsonSchemaLoader}, which knows nothing about this coordinator or its
 *   one-off bootstrap case. In practice this means a caller resolving anything beyond meta-kernel
 *   itself still needs to register meta-kernel explicitly first -- resolved *ordinarily* via {@code
 *   TsonSchemaResolver.resolveAll} against a coordinator whose own bootstrap branch supplies the
 *   structure namespace (never the raw/one-off linked bootstrap form directly -- {@code
 *   TsonSchemaRegistry#register} refuses any self-referential schema with {@code bootstrap() ==
 *   true}, see its own Javadoc) -- before asking this coordinator for anything that transitively
 *   imports it. Linking the one-off bootstrap result fixes *reading* through it correctly, not the
 *   separate *import-merge* requirement.</li>
 *   <li><b>Otherwise</b>, fetch {@code uri}'s own source text via this coordinator's own {@link
 *   TsonSchemaSource} (default: {@link TsonSchemaSource#registeredOnly()}, so nothing is fetched from
 *   anywhere unless a caller opts in), parse it, resolve it via a fresh {@code TsonSchemaResolver}
 *   constructed with *this same coordinator* (so that document's own {@code !!meta}/{@code
 *   !!import} targets resolve the same way, recursively, cache-then-bootstrap-then-fetch all the
 *   way down), then register and compile the result -- *this* result genuinely is cached, unlike
 *   meta-kernel's own bootstrap case above.</li>
 * </ol>
 */
public final class DefaultSchemaCoordinator implements SchemaCoordinator {

    private final TsonCompiledRegistry registry;
    private final TsonSchemaSource source;

    /** No fetch capability -- only meta-kernel's own one-off bootstrap and whatever's already registered/compiled ever resolve. */
    public DefaultSchemaCoordinator(TsonCompiledRegistry registry) {
        this(registry, TsonSchemaSource.registeredOnly());
    }

    public DefaultSchemaCoordinator(TsonCompiledRegistry registry, TsonSchemaSource source) {
        this.registry = registry;
        this.source = source;
    }

    @Override
    public TsonCompiledSchema resolve(String uri) {
        Optional<TsonCompiledSchema> cached = registry.get(uri);
        if (cached.isPresent()) {
            return cached.get();
        }
        if (BundledSchemaSource.META_KERNEL_ID.equals(uri)) {
            TsonSchema metaKernel = MetaKernelBootstrapResolver.getMetaKernelSchema();
            // TsonSchemaLinker.linkBootstrap runs its own materialization pass (synthesizing entries
            // for argument-bearing type-refs like enum's own `members: set<token>`) before compiling,
            // but persists nothing (not register -- TsonSchemaRegistry refuses a linked bootstrap
            // schema outright, always), so this is discarded immediately after: every call still
            // re-bootstraps and re-links from scratch, every time -- only the *quality* of the
            // one-off result changes (58 entries, not 49), not its lifetime.
            TsonLinkedSchema linked = TsonSchemaLinker.linkBootstrap(metaKernel);
            return TsonSchemaCompiler.compile(linked.schema(), registry.factories());
        }
        String sourceText = source.fetch(uri);
        SchemaDocument document = new TsonSchemaParser(sourceText).parseSchemaDocument();
        TsonSchemaResolver resolver = new TsonSchemaResolver(this);
        TsonSchema resolved = resolver.resolveAll(document);
        return registry.register(resolved);
    }
}
