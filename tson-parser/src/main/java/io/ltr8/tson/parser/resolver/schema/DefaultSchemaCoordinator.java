package io.ltr8.tson.parser.resolver.schema;

import io.ltr8.tson.parser.SchemaParser;
import io.ltr8.tson.parser.ast.schema.SchemaDocument;
import io.ltr8.tson.parser.resolver.schema.compiled.TsonCompiledRegistry;
import io.ltr8.tson.parser.resolver.schema.compiled.TsonSchemaParser;
import io.ltr8.tson.schema.MetaSchema;
import io.ltr8.tson.schema.TsonSchema;

import java.util.Optional;

/**
 * The default {@link SchemaCoordinator}: check the registry first, special-case meta-kernel's own
 * bootstrap next, and otherwise fetch, resolve, register, and compile a schema on demand.
 *
 * <ol>
 *   <li><b>Already compiled?</b> {@code registry.get(uri)} -- a plain cache hit, keyed the same
 *   (raw-{@code !!id}, not canonicalized) way {@link TsonCompiledRegistry} itself already is.</li>
 *   <li><b>Meta-kernel's own well-known identity?</b> Resolved via {@link MetaKernelParser#parse()}
 *   -- never through this coordinator's own generic path below, which would recurse forever: that
 *   path resolves a document via {@code SchemaResolver(this)}, and {@code SchemaResolver.resolveAll}
 *   itself calls back into {@link #resolve} for the document's own {@code !!meta} target -- fine for
 *   any real schema, whose {@code !!meta} points at something *other* than itself, but meta-kernel's
 *   own {@code !!meta} names itself (Part 2 §1.5's "one deliberate circularity"). This check runs
 *   *before* that path is ever reached, so the loop never starts. Compared by exact string, not
 *   canonical identity ({@code CanonicalIdentity} stays internal-by-convention to {@code
 *   tson-schema}, same reasoning as {@link TsonCompiledRegistry}'s own raw-id keying) -- a real,
 *   narrower guarantee, not an oversight.
 *
 *   <p><b>Deliberately a one-off, never registered/cached here</b> (on the user's own explicit
 *   direction): {@link MetaKernelParser#parse()}'s own output is compiled directly ({@code
 *   TsonSchemaParser.compile}, using this registry's own {@link
 *   io.ltr8.tson.parser.resolver.schema.compiled.ParserFactoryRegistry} so it reads the same way
 *   anything else compiled here would) but never passed to {@link TsonCompiledRegistry#register}.
 *   So every call for {@link #META_KERNEL_ID} that isn't already a cache hit re-bootstraps and
 *   re-compiles from scratch -- deliberately, not an oversight: the *permanent*, shared registry
 *   entry for meta-kernel is meant to come from an explicit, deliberate "load it (from disk, or
 *   eventually a real {@link SchemaSource}) and register it" step done once, elsewhere -- not
 *   implicitly, silently, the first time anything happens to ask for meta-kernel's own {@code
 *   !!meta}/{@code !!import} target. Until that explicit step exists and runs, this one-off bootstrap
 *   is what stands in for it, every time, so nothing is ever left unable to resolve at all.
 *
 *   <p><b>Two real, load-bearing consequences of never registering it here.</b> Skipping {@link
 *   TsonCompiledRegistry#register} also skips everything {@code SchemaRegistry#register} does on
 *   the way in ({@code SchemaValidator}'s own materialization/validation pass) -- so (1) this
 *   one-off reader has no synthesized entries for any argument-bearing {@code type_ref} (e.g.
 *   {@code enum}'s own {@code members: set<token>}; a real, registered meta-kernel has 58 entries,
 *   this one-off bootstrap has the raw 49), and (2) any *other* schema that {@code !!import}s
 *   meta-kernel (every real one does) will fail its own registration with "{@code !!import '...' is
 *   not registered}" unless meta-kernel has *separately* been registered first: {@code
 *   SchemaValidator}'s own import-merging (run inside {@code SchemaRegistry#register}, a step
 *   distinct from {@code SchemaResolver}'s own resolution-time import-merging above) resolves an
 *   import via {@code SchemaRegistry}'s own registered-only {@code SchemaLoader}, which knows
 *   nothing about this coordinator or its bootstrap case. In practice this means a caller resolving
 *   anything beyond meta-kernel itself needs to register meta-kernel explicitly first (e.g. {@code
 *   registry.register(MetaKernelParser.parse())}) before asking this coordinator for anything that
 *   transitively imports it.</li>
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

    /** Meta-kernel's own real, published identity -- the one URI this coordinator never tries to fetch or resolve the ordinary way. */
    public static final String META_KERNEL_ID = "https://tson.io/2026/32/m/meta-kernel.tn1";

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
    public TsonSchemaParser resolve(String uri) {
        Optional<TsonSchemaParser> cached = registry.get(uri);
        if (cached.isPresent()) {
            return cached.get();
        }
        if (META_KERNEL_ID.equals(uri)) {
            MetaSchema metaKernel = MetaKernelParser.parse();
            return TsonSchemaParser.compile(metaKernel, registry.factories());
        }
        String sourceText = source.fetch(uri);
        SchemaDocument document = new SchemaParser(sourceText).parseSchemaDocument();
        SchemaResolver resolver = new SchemaResolver(this);
        TsonSchema resolved = resolver.resolveAll(document);
        return registry.register(resolved);
    }
}
