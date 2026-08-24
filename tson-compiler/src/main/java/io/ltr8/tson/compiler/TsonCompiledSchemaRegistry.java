package io.ltr8.tson.compiler;

import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.compiler.reader.ValueReaderFactoryRegistry;
import io.ltr8.tson.compiler.reader.ValueReaderFactoryResolver;
import io.ltr8.tson.schema.TsonCanonicalIdentity;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.tree.TsonValue;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A per-mode registry of compiled <b>user</b> schemas, over a shared {@link TsonCompiledMetaRegistry}
 * (the bind-mode resolution core). The parallel to that class: {@code TsonCompiledMetaRegistry} holds the
 * compiled meta layer every schema resolves against; this holds compiled user schemas, read in one mode.
 * Built via {@link #tree} or {@link #bind} -- the read mode is which factory the registry was made with,
 * not a parameter threaded through compile, so two registries over one core read the same schema as a
 * queryable {@code TsonValue} tree or as real Java objects.
 *
 * <p><b>Resolution is always bind-anchored, so it is delegated to the core regardless of this registry's
 * own read mode.</b> Resolving a schema's own {@code !enum}/{@code !integer} instances binds them to
 * {@code schema.meta} objects (a DOM reader's {@code Map} output can't stand in), so every mode shares the
 * one bind-mode core for resolution; only the final compile of the already-resolved, already-linked schema
 * runs in this registry's mode. That compile is <b>standalone</b> (no governing-meta scoping): the
 * schema's constructor usage was already validated when it was linked.
 */
public final class TsonCompiledSchemaRegistry {

    /**
     * Which of the two factory sets this registry was built with. The read mode is otherwise invisible in
     * the type -- both modes are a {@code TsonCompiledSchemaRegistry} -- so a reader handed one can check
     * that it is the mode it can actually consume, rather than casting whatever comes back and failing at
     * the first value read. Package-private: a consumer picks a mode by calling {@link #tree} or {@link
     * #bind}, and never needs to ask afterwards.
     */
    enum Mode {
        TREE, BIND
    }

    private final TsonCompiledMetaRegistry core;
    private final ValueReaderFactoryResolver factories;
    private final Mode mode;
    private final Map<String, TsonCompiledSchema> compiled = new ConcurrentHashMap<>();

    private TsonCompiledSchemaRegistry(TsonCompiledMetaRegistry core, ValueReaderFactoryResolver factories, Mode mode) {
        this.core = core;
        this.factories = factories;
        this.mode = mode;
    }

    /** The read mode this registry compiles in -- see {@link Mode}. */
    Mode mode() {
        return mode;
    }

    /**
     * A registry that reads user schemas into an immutable, queryable {@link
     * TsonValue} tree -- structure-preserving (record vs map, array vs tuple) with
     * typed leaves and null-safe navigation, and no Java class per schema type (hence no {@code
     * DataBindContext}). The recommended read mode; {@link #bind} is the object-binding alternative.
     */
    public static TsonCompiledSchemaRegistry tree(TsonCompiledMetaRegistry core) {
        return new TsonCompiledSchemaRegistry(core, ValueReaderFactoryRegistry.tree(), Mode.TREE);
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
        return bind(core, context, true);
    }

    /**
     * {@link #bind(TsonCompiledMetaRegistry, DataBindContext)} with the schema-to-class agreement check made
     * optional -- {@code strict} false is the opt-out for a caller who means to read a document whose schema
     * declares more than their class holds (versioned evolution, where a v1 consumer reads a v2 document).
     * What it buys back is silence, so it is worth being sure: the dropped value is gone with no trace, and
     * the same symptom from a *mistake* is a field mysteriously holding its default.
     */
    public static TsonCompiledSchemaRegistry bind(TsonCompiledMetaRegistry core, DataBindContext context,
                                                   boolean strict) {
        return new TsonCompiledSchemaRegistry(core, ValueReaderFactoryRegistry.bind(context, strict), Mode.BIND);
    }

    /**
     * The compiled reader for the schema at {@code uri} -- resolved through the core, compiled in this
     * registry's mode, cached by canonical identity so repeated reads of the same schema compile once. The
     * core resolves the schema (into its own linked form) but does not compile it; this registry compiles
     * that linked form in its own mode, so a user schema is never bind-compiled in the core just to be read
     * here. The core is asked to resolve on every call (not only on a cache miss) so this reference's own
     * {@code ?sha256=} pin is verified against the identity's content each time ([TSON-SCHEMA] §10.2); the
     * core caches the resolution itself, so a repeat is cheap.
     */
    public TsonCompiledSchema get(String uri) {
        String identity = TsonCanonicalIdentity.canonicalize(uri);
        TsonLinkedSchema linked = core.resolveLinked(uri, identity, null);
        return compiledFor(identity, linked);
    }

    /**
     * {@link #get(String)} reporting every problem in the schema through {@code receiver} rather than
     * throwing at the first, returning {@code null} when anything was reported.
     *
     * <p>What a *data* read uses to say what is wrong with the schema the document names, so `tson validate`
     * and `tson compile` give the same account of the same broken schema instead of one flattening it to its
     * first error. Nothing is cached for a schema that reported: only a schema that resolved, linked and
     * compiled cleanly gets an entry.
     */
    public TsonCompiledSchema get(String uri, TsonDiagnosticsReceiver receiver) {
        String identity = TsonCanonicalIdentity.canonicalize(uri);
        TsonLinkedSchema linked = core.resolveLinked(uri, identity, receiver);
        if (linked == null) {
            return null;
        }
        return compiledFor(identity, linked);
    }

    /**
     * The cached compilation for {@code identity}, compiling on a miss. <b>A plain {@code get} first, and
     * {@code computeIfAbsent} only when it misses</b>: every read reaches this and all but the first hit,
     * and {@code computeIfAbsent} takes the bin's lock where a colliding key sits behind the first node,
     * where {@code get} never locks at all. On the miss the race is harmless either way -- two threads
     * compiling one schema, one entry kept -- so the atomicity is worth having and worth not paying for on
     * the path that does not need it.
     */
    private TsonCompiledSchema compiledFor(String identity, TsonLinkedSchema linked) {
        TsonCompiledSchema cached = compiled.get(identity);
        return cached != null ? cached : compiled.computeIfAbsent(identity, id -> compile(linked));
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
