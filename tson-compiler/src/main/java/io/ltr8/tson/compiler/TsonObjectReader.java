package io.ltr8.tson.compiler;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.tson.compiler.config.TsonAtomContext;
import io.ltr8.tson.compiler.reader.SchemalessObjectReader;
import io.ltr8.tson.compiler.stream.DocumentEnd;
import io.ltr8.tson.compiler.stream.DocumentStart;
import io.ltr8.tson.compiler.stream.TsonEvent;
import io.ltr8.tson.compiler.stream.TypeRef;

import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;

/**
 * Binds a TSON document to a Java object of a caller-chosen target class -- the class-driven read-side
 * front door, the inverse of {@link TsonObjectWriter} and the object-shaped peer of {@link TsonTreeReader}.
 *
 * <p><b>Two modes, fixed at construction.</b> A reader from {@link Tson#objectReader()} (the {@code
 * (TsonCompiledMetaRegistry, DataBindContext)} constructor) is <i>schema-aware</i>: a document that
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
 * be buffered before binding begins), reports through that context's one error model (a fail-fast context
 * throws {@link TsonReadException} at the first problem; a {@link TsonReadContext#collecting collecting}
 * one accumulates every independent problem and reads on), and has no positional form or schema-composed
 * defaults (a record must be written braced; an absent required field is a {@code FIELD_REQUIRED} problem).
 */
public final class TsonObjectReader {

    private final DataBindContext dataBindContext;
    private final SchemalessObjectReader schemaless;

    /** The bind-mode compiled-schema registry a schema-aware reader validates through, or {@code null} for a schemaless reader (any {@code !!schema} is then ignored). */
    private final TsonCompiledSchemaRegistry bind;

    /** Schema-aware -- validates a self-describing document against its {@code !!schema}, resolved through {@code core}'s own source. Used by {@link Tson#objectReader()}. */
    public TsonObjectReader(TsonCompiledMetaRegistry core, DataBindContext dataBindContext) {
        this.dataBindContext = dataBindContext;
        this.schemaless = new SchemalessObjectReader(dataBindContext);
        this.bind = TsonCompiledSchemaRegistry.bind(core, dataBindContext);
    }

    /** Schemaless -- binds to the target class alone, ignoring any {@code !!schema} the document declares. */
    public TsonObjectReader(DataBindContext context) {
        this.dataBindContext = context;
        this.schemaless = new SchemalessObjectReader(context);
        this.bind = null;
    }

    /** Schemaless, over {@link TsonAtomContext#defaultContext()}. */
    public TsonObjectReader() {
        this(TsonAtomContext.defaultContext());
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
     * Binds one value at {@code ctx}'s current position into {@code targetClass} -- the low-level form for
     * a caller managing their own {@link TsonReadContext} (e.g. a {@link TsonReadContext#collecting
     * collecting} one to gather every problem in one pass). Always schemaless and frame-free: it neither
     * inspects a {@code !!schema} (an arbitrary position carries no document framing to hold one) nor
     * checks for trailing content; use the {@code String}/{@code InputStream} entry points for a whole
     * self-describing document.
     */
    public <T> T read(TsonReadContext ctx, Class<T> targetClass) {
        return schemaless.read(ctx, targetClass);
    }

    // ── Internals ────────────────────────────────────────────────────────

    private <T> T readDocument(TsonDataStream stream, Class<T> type, boolean ignoreSchema) {
        Objects.requireNonNull(type, "type");
        TsonReadContext ctx = TsonReadContext.throwing(stream);
        DocumentStart start = (DocumentStart) ctx.next();
        T result = (ignoreSchema || bind == null || start.schema().isEmpty())
                ? schemaless.read(ctx, type)
                : readAgainstSchema(start.schema().get(), ctx, type);
        TsonEvent trailing = ctx.next();
        if (!(trailing instanceof DocumentEnd)) {
            throw new IllegalStateException("unexpected trailing event after the document's value: " + trailing);
        }
        return result;
    }

    private <T> T readAgainstSchema(String schemaUri, TsonReadContext ctx, Class<T> type) {
        RootReader root = select(schemaUri, ctx);
        Class<?> bound = boundClass(root.typeName());
        if (bound != null && !type.isAssignableFrom(bound)) {
            throw new TsonReadException(problem(Diagnostic.Code.TYPE_MISMATCH,
                    "the schema's root type `" + root.typeName() + "` binds to " + bound.getName()
                            + ", which is not assignable to the requested " + type.getName()));
        }
        Object value = root.reader().read(ctx);
        if (value != null && !type.isInstance(value)) {
            throw new TsonReadException(problem(Diagnostic.Code.TYPE_MISMATCH,
                    "the schema's root type `" + root.typeName() + "` produced a " + value.getClass().getName()
                            + ", not the requested " + type.getName()));
        }
        return type.cast(value);
    }

    /** The bound Java class the schema type {@code typeRefName} maps to, or {@code null} if it isn't name-bound (e.g. an atom root) -- the before-read type check then falls back to the post-read cast. */
    private Class<?> boundClass(String typeRefName) {
        try {
            return dataBindContext.getDescriptor(typeRefName).typeClass();
        } catch (DataBindException e) {
            return null;
        }
    }

    private RootReader select(String schemaUri, TsonReadContext ctx) {
        TsonCompiledSchema compiled;
        try {
            compiled = bind.get(schemaUri);
        } catch (RuntimeException e) {
            throw new TsonReadException(problem(Diagnostic.Code.SCHEMA_ERROR, e.getMessage()));
        }
        if (!(ctx.peek() instanceof TypeRef typeRef)) {
            throw new TsonReadException(problem(Diagnostic.Code.VALIDATION_ERROR,
                    "data declares a !!schema but has no root type-ref (e.g. `!person`) to select a type"));
        }
        try {
            return new RootReader(compiled.get(typeRef.name()), typeRef.name());
        } catch (RuntimeException e) {
            throw new TsonReadException(problem(Diagnostic.Code.UNKNOWN_TYPE, e.getMessage()));
        }
    }

    private record RootReader(TsonValueReader<?> reader, String typeName) {
    }

    private static Diagnostic problem(Diagnostic.Code code, String message) {
        return new Diagnostic("", code, message, "", "", Optional.empty(), Optional.empty());
    }
}
