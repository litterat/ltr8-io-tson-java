package io.ltr8.tson.parser.resolver.schema;

import io.ltr8.tson.parser.SchemaParser;
import io.ltr8.tson.parser.ast.schema.SchemaDocument;
import io.ltr8.tson.parser.resolver.schema.compiled.TsonCompiledRegistry;
import io.ltr8.tson.parser.resolver.schema.compiled.TsonCompiledSchema;
import io.ltr8.tson.parser.resolver.schema.compiled.TsonSchemaCompiler;
import io.ltr8.tson.schema.LinkedTsonSchema;
import io.ltr8.tson.schema.SchemaRegistry;
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
 *   MetaKernelParser#getMetaKernelSchema()} -- never through this coordinator's own generic path
 *   below, which would recurse forever: that
 *   path resolves a document via {@code SchemaResolver(this)}, and {@code SchemaResolver.resolveAll}
 *   itself calls back into {@link #resolve} for the document's own {@code !!meta} target -- fine for
 *   any real schema, whose {@code !!meta} points at something *other* than itself, but meta-kernel's
 *   own {@code !!meta} names itself (Part 2 §1.5's "one deliberate circularity"). This check runs
 *   *before* that path is ever reached, so the loop never starts. Compared by exact string, not
 *   canonical identity ({@code CanonicalIdentity} stays internal-by-convention to {@code
 *   tson-schema}, same reasoning as {@link TsonCompiledRegistry}'s own raw-id keying) -- a real,
 *   narrower guarantee, not an oversight.
 *
 *   <p><b>Deliberately a one-off, never registered/cached in the *shared* registry</b> (on the
 *   user's own explicit direction): {@link MetaKernelParser#getMetaKernelSchema()}'s own output is
 *   run through a fresh, throwaway {@code SchemaRegistry} -- created and discarded right here, never
 *   the shared {@link #registry} this coordinator wraps -- purely so {@code SchemaLinker}'s own
 *   materialization/validation pass runs (synthesizing entries for argument-bearing {@code
 *   type_ref}s, e.g. {@code enum}'s own {@code members: set<token>}) before compiling ({@code
 *   TsonSchemaCompiler.compile}, using this coordinator's own {@link
 *   io.ltr8.tson.parser.resolver.schema.compiled.TsonParserFactoryRegistry} so it reads the same way
 *   anything else compiled here would). The *materialized* result is never passed to {@link
 *   TsonCompiledRegistry#register} -- so every call for {@link BundledSchemaSource#META_KERNEL_ID}
 *   that isn't already a cache hit re-bootstraps, re-materializes, and re-compiles from scratch,
 *   every time -- only the
 *   *quality* of the one-off result changed (58 entries, matching a genuinely registered meta-kernel,
 *   not the raw 49), never its lifetime. Deliberately, not an oversight: the *permanent*, shared
 *   registry entry for meta-kernel is meant to come from an explicit, deliberate "load it (from disk,
 *   or eventually a real {@link SchemaSource}) and register it" step done once, elsewhere -- not
 *   implicitly, silently, the first time anything happens to ask for meta-kernel's own {@code
 *   !!meta}/{@code !!import} target. Until that explicit step exists and runs, this one-off bootstrap
 *   is what stands in for it, every time, so nothing is ever left unable to resolve at all.
 *
 *   <p><b>One real, load-bearing consequence remains, even with materialization fixed.</b> Any
 *   *other* schema that {@code !!import}s meta-kernel (every real one does) will still fail its own
 *   registration with "{@code !!import '...' is not registered}" unless meta-kernel has *separately*
 *   been registered in the *shared* registry first: {@code SchemaLinker}'s own import-merging
 *   (run inside {@code SchemaRegistry#register}, a step distinct from {@code SchemaResolver}'s own
 *   resolution-time import-merging above) resolves an import via {@code SchemaRegistry}'s own
 *   registered-only {@code SchemaLoader}, which knows nothing about this coordinator, its bootstrap
 *   case, or the throwaway registry used to materialize it. In practice this means a caller
 *   resolving anything beyond meta-kernel itself still needs to register meta-kernel explicitly
 *   first (e.g. {@code registry.register(registry.schemaRegistry().materializeBootstrap(
 *   MetaKernelParser.getMetaKernelSchema()))} -- {@code SchemaRegistry#register} itself now refuses
 *   the raw, unmaterialized bootstrap form outright, see its own Javadoc) before asking this
 *   coordinator for anything that transitively imports it -- materializing the one-off bootstrap result fixes
 *   *reading* through it correctly, not the separate *import-merge* requirement.</li>
 *   <li><b>Otherwise</b>, fetch {@code uri}'s own source text via this coordinator's own {@link
 *   SchemaSource} (default: {@link SchemaSource#registeredOnly()}, so nothing is fetched from
 *   anywhere unless a caller opts in), parse it, resolve it via a fresh {@code SchemaResolver}
 *   constructed with *this same coordinator* (so that document's own {@code !!meta}/{@code
 *   !!import} targets resolve the same way, recursively, cache-then-bootstrap-then-fetch all the
 *   way down), then register and compile the result -- *this* result genuinely is cached, unlike
 *   meta-kernel's own bootstrap case above.</li>
 * </ol>
 */
public final class DefaultSchemaCoordinator implements SchemaCoordinator {

    private final TsonCompiledRegistry registry;
    private final SchemaSource source;

    /** No fetch capability -- only meta-kernel's own one-off bootstrap and whatever's already registered/compiled ever resolve. */
    public DefaultSchemaCoordinator(TsonCompiledRegistry registry) {
        this(registry, SchemaSource.registeredOnly());
    }

    public DefaultSchemaCoordinator(TsonCompiledRegistry registry, SchemaSource source) {
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
            TsonSchema metaKernel = MetaKernelParser.getMetaKernelSchema();
            // A fresh, throwaway SchemaRegistry -- never the shared one this coordinator wraps --
            // purely so SchemaLinker's own materialization pass runs (synthesizing entries for
            // argument-bearing type-refs like enum's own `members: set<token>`) before compiling.
            // linkBootstrap (not register -- SchemaRegistry now refuses a linked bootstrap schema
            // outright, always) doesn't persist anything either, so this is still discarded
            // immediately after: every call still re-bootstraps and re-links from scratch, exactly
            // as before -- only the *quality* of the one-off result changes (58 entries, not 49),
            // not its lifetime.
            LinkedTsonSchema linked = new SchemaRegistry().linkBootstrap(metaKernel);
            return TsonSchemaCompiler.compile(linked.schema(), registry.factories());
        }
        String sourceText = source.fetch(uri);
        SchemaDocument document = new SchemaParser(sourceText).parseSchemaDocument();
        SchemaResolver resolver = new SchemaResolver(this);
        TsonSchema resolved = resolver.resolveAll(document);
        return registry.register(resolved);
    }
}
