package io.ltr8.tson.compiler;

import io.ltr8.tson.base.CountingReceiver;
import io.ltr8.tson.base.MissingBindingException;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.ValueEntry;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.compiler.reader.ValueReaderFactoryRegistry;
import io.ltr8.tson.compiler.reader.ValueReaderFactoryResolver;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.tree.TsonValue;

import io.ltr8.tson.schema.TsonSchema;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
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
 * runs in this registry's mode, through the mode's factory set alone: the schema's constructor usage was already
 * validated when it was linked.
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
     * DataNameBinder}), not a plain {@code Map}. {@code Tson.of} hands it the one context the core resolves
     * schemas through, so the class a schema's value entry binds to binds the same type's values in data. A schema
     * obtained here is refused where its closure holds a value entry no class binds ({@code refuseUnbound}).
     */
    public static TsonCompiledSchemaRegistry bind(TsonCompiledMetaRegistry core, DataBindContext context) {
        return new TsonCompiledSchemaRegistry(core, ValueReaderFactoryRegistry.bind(context), Mode.BIND);
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
        String identity = CanonicalIdentity.canonicalize(uri);
        TsonLinkedSchema linked = core.resolveLinked(uri, identity, null);
        refuseUnbound(linked, null);
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
    public TsonCompiledSchema get(String uri, DiagnosticsReceiver receiver) {
        String identity = CanonicalIdentity.canonicalize(uri);
        TsonLinkedSchema linked = core.resolveLinked(uri, identity, receiver);
        if (linked == null || refuseUnbound(linked, receiver)) {
            return null;
        }
        return compiledFor(identity, linked);
    }

    /**
     * {@link #get(String, DiagnosticsReceiver)} for a read under several schemas, as a data document importing all of
     * them is read ([TSON-DATA] §2.2): one namespace over their closures, with the collision rule a schema's imports
     * are judged by ([TSON-SCHEMA] §2.2.3) -- a name two of them declare differently refuses the whole namespace.
     * One import (in any number of spellings) is that schema's own compilation.
     *
     * <p>Several are linked as a schema of no entries of its own importing each of them, under an identity derived
     * from theirs ({@link #closureIdentity}) that is never registered, and compiled once per set.
     */
    public TsonCompiledSchema get(List<String> imports, DiagnosticsReceiver receiver) {
        List<String> identities = imports.stream().map(CanonicalIdentity::canonicalize).distinct().toList();
        if (identities.size() == 1) {
            return get(imports.getFirst(), receiver);
        }
        for (String uri : imports) {
            // Each resolved on its own first: its pin verified, and every problem in it reported as for one import.
            if (core.resolveLinked(uri, CanonicalIdentity.canonicalize(uri), receiver) == null) {
                return null;
            }
        }
        String identity = closureIdentity(identities);
        TsonCompiledSchema cached = compiled.get(identity);
        if (cached != null) {
            return cached;
        }
        CountingReceiver counting = new CountingReceiver(receiver);
        TsonLinkedSchema linked = TsonSchemaLinker.link(new TsonSchema(identity, imports, Map.of()),
                core.schemaRegistry(), counting, core.identifierPolicy());
        return counting.reported() || refuseUnbound(linked, receiver) ? null : compiledFor(identity, linked);
    }

    /**
     * The identity a set of imports is linked under: a path-only identity ([TSON-DATA] §2.2.1) named by a digest of
     * the canonical identities in header order, so one set always names one compilation.
     */
    private static String closureIdentity(List<String> identities) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\n", identities).getBytes(StandardCharsets.UTF_8));
            return "/imports/" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every Java platform", e);
        }
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

    /** Compiles an already-resolved, already-linked schema in this registry's mode, uncached. */
    public TsonCompiledSchema compile(TsonLinkedSchema linked) {
        refuseUnbound(linked, null);
        return TsonSchemaCompiler.compile(linked, factories);
    }

    /**
     * A schema obtained to bind holds no value entry no class binds ([TSON-SCHEMA] §4.1). The resolution core holds
     * such an entry as a tree, so the schema loads and validates without host classes; a consumer binding under it
     * expects every value of its closure as the class its bind context names, and a tree in its place is the
     * consumer's wiring, a {@code BIND_MISMATCH} at the entry. Each such entry of the closure is reported through
     * {@code receiver}, or the first thrown where it is {@code null}; true where one was reported. A tree registry
     * refuses nothing.
     */
    private boolean refuseUnbound(TsonLinkedSchema linked, DiagnosticsReceiver receiver) {
        if (mode != Mode.BIND) {
            return false;
        }
        boolean refused = false;
        for (Map.Entry<String, Top> entry : linked.schema().entries().entrySet()) {
            if (!(entry.getValue() instanceof ValueEntry value) || !(value.value() instanceof TsonValue)) {
                continue;
            }
            String name = entry.getKey();
            MissingBindingException unbound = new MissingBindingException("'" + name + "' is a value of the type '"
                    + value.type() + "', and the bind context binds no class for it -- the schema loads and validates "
                    + "with the value held as a tree, and is bound only where every value of its closure has a class "
                    + "to bind to: bind '" + value.type() + "' (and the types its fields name) to a class");
            if (receiver == null) {
                throw unbound;
            }
            receiver.report(TsonDiagnostics.ofSchemaBindMismatch(
                    linked.originOf(name), name, unbound,
                    linked.schema().positionOf(name)));
            refused = true;
        }
        return refused;
    }

    /** The bind-mode resolution core this registry reads user schemas against. */
    public TsonCompiledMetaRegistry core() {
        return core;
    }
}
