package io.ltr8.tson.compiler;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.tson.compiler.config.TsonAtomContext;
import io.ltr8.tson.compiler.reader.EventSkip;
import io.ltr8.tson.compiler.reader.SchemalessObjectReader;
import io.ltr8.tson.compiler.stream.DocumentEnd;
import io.ltr8.tson.compiler.stream.DocumentStart;
import io.ltr8.tson.compiler.stream.TsonEvent;
import io.ltr8.tson.compiler.stream.TypeRef;

import java.io.InputStream;
import java.util.Objects;

/**
 * Binds a TSON document to a Java object of a caller-chosen target class -- the class-driven read-side
 * front door, the inverse of {@link TsonObjectWriter} and the object-shaped peer of {@link TsonTreeReader}.
 *
 * <p><b>Two modes, fixed at construction.</b> A reader from {@code Tson#objectReader()} (the {@code
 * (TsonCompiledSchemaRegistry, DataBindContext)} constructor) is <i>schema-aware</i>: a document that
 * declares a {@code !!schema} is validated against it as it binds -- the schema resolves through that
 * environment's own source, the document's root type-ref (e.g. {@code !person}) selects the type, and a
 * target class the schema's root type does not bind to is rejected up front, before the value is read
 * ({@link Diagnostic.Code#TYPE_MISMATCH}). A reader built standalone ({@link #TsonObjectReader()} / {@link
 * #TsonObjectReader(DataBindContext)}) is <i>schemaless</i>: {@code targetClass} is the whole contract and
 * any {@code !!schema} the document declares is ignored -- the way a JSON binder treats the target class
 * itself as the schema. When no {@code !!schema} is present the two modes behave identically. {@link
 * #readWithoutSchema} forces the schemaless path on a schema-aware reader (bind a self-describing document
 * without validating it -- e.g. when its schema is unavailable and the class is the intended contract).
 *
 * <p>Either way the class-driven binding itself is done by {@link SchemalessObjectReader}, driven by the
 * target class's own {@code tson-bind} {@link DataClass} descriptor: it streams events off a {@link
 * TsonReadContext} (never materializing a whole {@code DataValue} tree first, so a large document need not
 * be buffered before binding begins), reports through that context's one error model, and has no positional
 * form or schema-composed defaults (a record must be written braced; an absent required field is a {@code
 * FIELD_REQUIRED} problem).
 *
 * <p>A read is fail-fast by default, throwing {@link TsonReadException} at the first problem. {@link
 * #withDiagnostics} swaps that for any other {@link TsonDiagnosticsReceiver} -- a collector gathers every
 * problem in one pass and still hands back the (possibly partial) object, in schema-aware and schemaless
 * mode alike.
 *
 * <p>A schemaless bind also holds a wire type-ref to account: a built-in name must sit on a token and
 * satisfy its atom, and any other name must name the target being bound. {@link #preservingUnknownTypeRefs}
 * is the passthrough opt-out.
 */
public final class TsonObjectReader {

    private final DataBindContext dataBindContext;
    private final SchemalessObjectReader schemaless;

    /** The bind-mode compiled-schema registry a schema-aware reader validates through, or {@code null} for a schemaless reader (any {@code !!schema} is then ignored). */
    private final TsonCompiledSchemaRegistry bind;

    /** Where this reader's reads report their problems -- fail-fast unless {@link #withDiagnostics} said otherwise. */
    private final TsonDiagnosticsReceiver receiver;

    /** The schema {@link #readAs} validates against, or {@code null} until {@link #withSchema} names one. */
    private final String schemaUri;

    /**
     * Schema-aware -- validates a self-describing document against its {@code !!schema}, resolved and
     * compiled through {@code bind}. Used by {@code Tson#objectReader()}.
     *
     * <p><b>Takes the registry rather than building one</b>, so a caller holding a registry shares its
     * compiled-schema cache with every reader made from it instead of each reader compiling the same schema
     * again. {@code dataBindContext} should be the one {@code bind} was built with -- it binds the
     * schemaless path, which the registry's own readers don't cover.
     *
     * @throws IllegalArgumentException if {@code bind} is a tree-mode registry, whose readers produce {@code
     *         TsonValue}s rather than the bound objects this reader hands back
     */
    public TsonObjectReader(TsonCompiledSchemaRegistry bind, DataBindContext dataBindContext) {
        this(dataBindContext, new SchemalessObjectReader(dataBindContext),
                requireBindMode(bind), TsonDiagnosticsReceiver.throwing(), null);
    }

    /**
     * The compiled-schema registry this reader validates through, or {@code null} if it is schemaless.
     * Package-private, for the same reason as {@link TsonTreeReader#compiledSchemas()}: sharing one cache
     * is otherwise unobservable, since compiling twice differs from compiling once only in cost.
     */
    TsonCompiledSchemaRegistry compiledSchemas() {
        return bind;
    }

    /** Rejects a wrong-mode registry where it is handed over, rather than at the first value it fails to bind. */
    private static TsonCompiledSchemaRegistry requireBindMode(TsonCompiledSchemaRegistry bind) {
        if (bind.mode() != TsonCompiledSchemaRegistry.Mode.BIND) {
            throw new IllegalArgumentException("a TsonObjectReader needs an object-binding registry "
                    + "(TsonCompiledSchemaRegistry.bind(core, context)) -- a tree-mode one reads to TsonNodes");
        }
        return bind;
    }

    /** Schemaless -- binds to the target class alone, ignoring any {@code !!schema} the document declares. */
    public TsonObjectReader(DataBindContext context) {
        this(context, new SchemalessObjectReader(context), null, TsonDiagnosticsReceiver.throwing(), null);
    }

    /** Schemaless, over {@link TsonAtomContext#defaultContext()}. */
    public TsonObjectReader() {
        this(TsonAtomContext.defaultContext());
    }

    /** Shares {@code bind} and {@code schemaless} rather than rebuilding them -- a derived reader must keep the original's compiled-schema cache, not start an empty one. */
    private TsonObjectReader(DataBindContext dataBindContext, SchemalessObjectReader schemaless,
                             TsonCompiledSchemaRegistry bind, TsonDiagnosticsReceiver receiver, String schemaUri) {
        this.dataBindContext = dataBindContext;
        this.schemaless = schemaless;
        this.bind = bind;
        this.receiver = receiver;
        this.schemaUri = schemaUri;
    }

    /**
     * This reader bound to the schema {@code schemaUri} names, for {@link #readAs} -- a new reader, leaving
     * this one unchanged, sharing its compiled-schema registry. The schema is resolved through the same
     * source and cache a self-describing document's own {@code !!schema} goes through, so it must already be
     * registered (e.g. via {@code Tson#resolve}) or be servable by the configured {@code TsonSchemaSource}.
     */
    public TsonObjectReader withSchema(String schemaUri) {
        if (bind == null) {
            throw new IllegalStateException("a schemaless TsonObjectReader has no schema environment to resolve '"
                    + schemaUri + "' through -- obtain one from Tson.objectReader()");
        }
        return new TsonObjectReader(dataBindContext, schemaless, bind, receiver, schemaUri);
    }

    /**
     * This reader, reporting through {@code receiver} instead of throwing at the first problem -- a new reader,
     * leaving this one unchanged, sharing its compiled-schema registry:
     *
     * <pre>{@code
     * var problems = TsonDiagnosticsReceiver.collecting();
     * Server server = tson.objectReader().withDiagnostics(problems).read(source, Server.class);
     * problems.diagnostics();      // every problem, alongside a possibly-partial object
     * }</pre>
     *
     * <p>Applies to the whole-document entry points only. {@link #read(TsonReadContext, Class)} takes a context
     * that carries its own receiver, and that one wins.
     */
    public TsonObjectReader withDiagnostics(TsonDiagnosticsReceiver receiver) {
        return new TsonObjectReader(dataBindContext, schemaless, bind, receiver, schemaUri);
    }

    /**
     * This reader, ignoring a type-ref that links to nothing instead of reporting it -- a new reader, leaving
     * this one unchanged, sharing its compiled-schema registry.
     *
     * <p>A schemaless bind reports a type-ref naming neither a built-in type nor the target being bound,
     * rather than treating it as a marker to skip past ({@code SPEC-FEEDBACK.md} #7). This is the
     * forward-compatible passthrough that entry asks for: a document tagged with names this reader knows
     * nothing about still binds on the strength of the target class alone. Built-in names are still checked,
     * so {@code !uuid nope} remains a problem. Affects the schemaless path only.
     */
    public TsonObjectReader preservingUnknownTypeRefs() {
        return new TsonObjectReader(dataBindContext, SchemalessObjectReader.preserving(dataBindContext),
                bind, receiver, schemaUri);
    }

    // ── Whole-document entry points ──────────────────────────────────────

    /** Reads {@code source}'s whole document into {@code targetClass}, fail-fast -- validated against its {@code !!schema} if this reader is schema-aware and the document declares one, schemaless otherwise. */
    public <T> T read(String source, Class<T> targetClass) {
        return readDocument(new TsonDataStream(source), targetClass, false);
    }

    /** {@link #read(String, Class)} straight off a stream -- binds {@code source}'s bytes (UTF-8) genuinely, never buffering the whole document into a {@code String} first; {@code source} is not closed here. */
    public <T> T read(InputStream source, Class<T> targetClass) {
        return readDocument(new TsonDataStream(source), targetClass, false);
    }

    /** Like {@link #read(String, Class)} but always schemaless -- binds to {@code targetClass} without validating, even when the document declares a {@code !!schema}. (A schemaless reader's {@link #read} already does this.) */
    public <T> T readWithoutSchema(String source, Class<T> targetClass) {
        return readDocument(new TsonDataStream(source), targetClass, true);
    }

    /** {@link #readWithoutSchema(String, Class)} straight off a stream. */
    public <T> T readWithoutSchema(InputStream source, Class<T> targetClass) {
        return readDocument(new TsonDataStream(source), targetClass, true);
    }

    /**
     * Binds {@code source} as {@code typeName}, declared by the schema {@link #withSchema} named -- for data
     * that isn't self-describing, where you hold the schema out of band. The caller supplies what a {@code
     * !!schema} plus a root type-ref would otherwise say, and validation (including the up-front check that
     * {@code targetClass} can hold that type) is identical either way.
     */
    public <T> T readAs(String source, String typeName, Class<T> targetClass) {
        return readDocumentAs(new TsonDataStream(source), typeName, targetClass);
    }

    /** {@link #readAs(String, String, Class)} straight off a stream. */
    public <T> T readAs(InputStream source, String typeName, Class<T> targetClass) {
        return readDocumentAs(new TsonDataStream(source), typeName, targetClass);
    }

    /**
     * Binds one value at {@code ctx}'s current position into {@code targetClass} -- the low-level form for a
     * caller managing their own {@link TsonReadContext}, whose receiver decides where problems go. Always
     * schemaless and frame-free: it neither inspects a {@code !!schema} (an arbitrary position carries no
     * document framing to hold one) nor checks for trailing content; use the {@code String}/{@code
     * InputStream} entry points, with {@link #withDiagnostics} if you want to collect, for a whole
     * self-describing document.
     */
    public <T> T read(TsonReadContext ctx, Class<T> targetClass) {
        return schemaless.read(ctx, targetClass);
    }

    // ── Internals ────────────────────────────────────────────────────────

    private <T> T readDocument(TsonDataStream stream, Class<T> type, boolean ignoreSchema) {
        Objects.requireNonNull(type, "type");
        TsonReadContext ctx = TsonReadContext.of(stream, receiver);
        DocumentStart start = (DocumentStart) ctx.next();
        T result = (ignoreSchema || bind == null || start.schema().isEmpty())
                ? schemaless.read(ctx, type)
                : readAgainstSchema(start.schema().get(), ctx, type, null);
        requireDocumentEnd(ctx);
        return result;
    }

    private <T> T readDocumentAs(TsonDataStream stream, String typeName, Class<T> type) {
        Objects.requireNonNull(type, "type");
        if (schemaUri == null) {
            throw new IllegalStateException("readAs needs a schema -- call withSchema(uri) first");
        }
        TsonReadContext ctx = TsonReadContext.of(stream, receiver);
        ctx.next(); // DocumentStart -- any !!schema it declares is overridden by withSchema
        T result = readAgainstSchema(schemaUri, ctx, type, typeName);
        requireDocumentEnd(ctx);
        return result;
    }

    /**
     * Pulls the event after the document's value, which must be {@code DocumentEnd}.
     *
     * <p><b>The pull is the point, not the assertion.</b> {@link TsonDataStream} is lazy, and its root frame
     * is what rejects trailing content -- but only when something asks for an event past the root value. Drop
     * this call and {@code "{ a: 1 } junk"} reads clean. The {@code instanceof} check is then belt-and-braces:
     * the pull itself throws {@code TsonParseException} first on any real document.
     */
    private static void requireDocumentEnd(TsonReadContext ctx) {
        TsonEvent trailing = ctx.next();
        if (!(trailing instanceof DocumentEnd)) {
            throw new IllegalStateException("unexpected trailing event after the document's value: " + trailing);
        }
    }

    /**
     * Binds the root value against {@code schemaUri}'s type -- {@code typeName} when {@link #readAs} supplied
     * one, else the document's own root type-ref.
     *
     * <p>A problem reaching the schema, or a root type this target class can't hold, is reported through {@code
     * ctx} like any other -- so a fail-fast reader still throws while a collecting one gets it as a {@link
     * Diagnostic}, the same promise {@code Tson#validate} makes. Where the failure is noticed before the value
     * is read, the value is skipped so the stream still lands on {@code DocumentEnd}.
     */
    private <T> T readAgainstSchema(String schemaUri, TsonReadContext ctx, Class<T> type, String typeName) {
        RootReader root = select(schemaUri, ctx, typeName);
        if (root == null) {
            return null;
        }
        Class<?> bound = boundClass(root.typeName());
        if (bound != null && !type.isAssignableFrom(bound)) {
            return abandon(ctx, Diagnostic.Code.TYPE_MISMATCH,
                    "the schema's root type `" + root.typeName() + "` binds to " + bound.getName()
                            + ", which is not assignable to the requested " + type.getName());
        }
        Object value = root.reader().read(ctx);
        if (value != null && !type.isInstance(value)) {
            // The value is already consumed, so there is nothing left to skip -- just report and yield none.
            ctx.report(Diagnostic.Code.TYPE_MISMATCH,
                    "the schema's root type `" + root.typeName() + "` produced a " + value.getClass().getName()
                            + ", not the requested " + type.getName(), "", "");
            return null;
        }
        return type.cast(value);
    }

    /** Reports {@code code}/{@code message}, discards the root value, and yields no object -- see {@link #readAgainstSchema}. */
    private static <T> T abandon(TsonReadContext ctx, Diagnostic.Code code, String message) {
        ctx.report(code, message, "", "");
        EventSkip.dataValue(ctx);
        return null;
    }

    /** The bound Java class the schema type {@code typeRefName} maps to, or {@code null} if it isn't name-bound (e.g. an atom root) -- the before-read type check then falls back to the post-read cast. */
    private Class<?> boundClass(String typeRefName) {
        try {
            return dataBindContext.getDescriptor(typeRefName).typeClass();
        } catch (DataBindException e) {
            return null;
        }
    }

    /** The schema's root reader, or {@code null} when the problem has been reported and the value skipped. */
    private RootReader select(String schemaUri, TsonReadContext ctx, String typeName) {
        TsonCompiledSchema compiled;
        try {
            // See TsonTreeReader.readAgainstSchema: every problem with the schema, reported to this reader's
            // own receiver so each keeps the declaration and position it belongs to rather than being
            // rebuilt from the data cursor.
            compiled = bind.get(schemaUri, receiver);
        } catch (RuntimeException e) {
            return abandon(ctx, Diagnostic.Code.SCHEMA_ERROR, e.getMessage());
        }
        if (compiled == null) {
            EventSkip.dataValue(ctx);
            return null;
        }
        String name = typeName;
        if (name == null) {
            if (!(ctx.peek() instanceof TypeRef typeRef)) {
                return abandon(ctx, Diagnostic.Code.VALIDATION_ERROR,
                        "data declares a !!schema but has no root type-ref (e.g. `!person`) to select a type");
            }
            name = typeRef.name();
        }
        try {
            return new RootReader(compiled.get(name), name);
        } catch (RuntimeException e) {
            return abandon(ctx, Diagnostic.Code.UNKNOWN_TYPE, e.getMessage());
        }
    }

    private record RootReader(TsonTypeReader<?> reader, String typeName) {
    }
}
