package io.ltr8.tson.compiler;

import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.compiler.config.ValueReaderFactoryResolver;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchemaRegistry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A per-mode registry of compiled <b>user</b> schemas, over a shared {@link TsonCompiledMetaRegistry}
 * (the bind-mode resolution core). The parallel to that class: {@code TsonCompiledMetaRegistry} holds the
 * compiled meta layer every schema resolves against; this holds compiled user schemas, read in one mode.
 * Built via {@link #dom} or {@link #bind} -- the read mode is which factory the registry was made with,
 * not a parameter threaded through compile, so two registries over one core read the same schema as plain
 * {@code Map}/{@code List} or as real Java objects.
 *
 * <p><b>Resolution is always bind-anchored, so it is delegated to the core regardless of this registry's
 * own read mode.</b> Resolving a schema's own {@code !enum}/{@code !integer} instances binds them to
 * {@code schema.meta} objects (a DOM reader's {@code Map} output can't stand in), so every mode shares the
 * one bind-mode core for resolution; only the final compile of the already-resolved, already-linked schema
 * runs in this registry's mode. That compile is <b>standalone</b> (no governing-meta scoping): the
 * schema's constructor usage was already validated when it was linked.
 */
public final class TsonCompiledSchemaRegistry {

    private final TsonCompiledMetaRegistry core;
    private final ValueReaderFactoryResolver factories;
    private final Map<String, TsonCompiledSchema> compiled = new ConcurrentHashMap<>();

    private TsonCompiledSchemaRegistry(TsonCompiledMetaRegistry core, ValueReaderFactoryResolver factories) {
        this.core = core;
        this.factories = factories;
    }

    /**
     * A registry that reads user schemas to DOM values -- a record as a plain {@code Map<String, Object>},
     * an array as a {@code List}, and so on, with no Java class per schema type (hence no {@code
     * DataBindContext}).
     */
    public static TsonCompiledSchemaRegistry dom(TsonCompiledMetaRegistry core) {
        return new TsonCompiledSchemaRegistry(core, TsonSchemaCompiler.dom());
    }

    /**
     * A registry that reads user schemas to real, object-bound Java values -- a {@code RecordBindReader}
     * produces a bound object (a caller's own class, resolved via {@code context}'s own {@code
     * DataNameBinder}), not a plain {@code Map}. {@code context} is the caller's own read-side binding
     * configuration, deliberately independent of the object-binding context the core uses internally to
     * resolve meta-schema instances into {@code schema.meta} objects -- a caller binding {@code person}
     * data to their own {@code Person} class supplies a {@code context} whose {@code DataNameBinder} knows
     * that mapping.
     */
    public static TsonCompiledSchemaRegistry bind(TsonCompiledMetaRegistry core, DataBindContext context) {
        return new TsonCompiledSchemaRegistry(core, TsonSchemaCompiler.bind(context));
    }

    /**
     * The compiled reader for the schema at {@code uri} -- resolved through the core, compiled in this
     * registry's mode, cached by canonical identity so repeated reads of the same schema compile once.
     * The core is asked to resolve on every call (not only on a cache miss) so this reference's own {@code
     * ?sha256=} pin is verified against the identity's content each time ([TSON-SCHEMA] §10.2); the core
     * caches the resolution itself, so a repeat is cheap.
     */
    public TsonCompiledSchema get(String uri) {
        core.load(uri);
        String identity = TsonSchemaRegistry.canonicalIdentity(uri);
        return compiled.computeIfAbsent(identity, id -> compile(core.schemaRegistry().get(uri).orElseThrow(() ->
                new IllegalStateException("schema \"" + uri + "\" resolved but is not registered"))));
    }

    /** Compiles an already-resolved, already-linked schema in this registry's mode -- standalone, uncached. */
    public TsonCompiledSchema compile(TsonLinkedSchema linked) {
        return TsonSchemaCompiler.compile(linked, factories);
    }

    /** The bind-mode resolution core this registry reads user schemas against. */
    public TsonCompiledMetaRegistry core() {
        return core;
    }
}
