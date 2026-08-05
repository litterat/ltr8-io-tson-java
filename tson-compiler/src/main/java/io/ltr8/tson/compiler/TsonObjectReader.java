package io.ltr8.tson.compiler;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.tson.compiler.atom.BuiltinTypeVocabulary;
import io.ltr8.tson.compiler.base.BaseTypeResolver;
import io.ltr8.tson.compiler.config.TsonAtomContext;
import io.ltr8.tson.compiler.reader.SchemalessObjectReader;
import io.ltr8.tson.compiler.stream.DocumentStart;
import io.ltr8.tson.compiler.stream.TsonEvent;
import io.ltr8.tson.compiler.stream.TypeRef;

import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;

/**
 * Binds a TSON document to a Java object -- schemaless (Class 1) binding driven by the target Java
 * class's own {@code tson-bind} {@link DataClass} descriptor, which is in effect the schema the data
 * must satisfy. The reflective, class-driven counterpart to the schema-driven {@link TsonValueReader}
 * (which validates against a resolved TSON schema instead), and the read-side inverse of {@link
 * TsonObjectWriter}. {@code tson-bind}, what this is built on, has no dependency on {@code
 * tson-compiler}/{@code tson-schema} at all, so depending on it directly here is clean -- which is
 * also what lets schema resolution (constructor application, atom refinement, §5.5) use this binding
 * layer directly, in the same module, without a cycle.
 *
 * <p><b>Streams its events, like {@link TsonValueReader}.</b> The value is read one {@link
 * TsonEvent} at a time off a {@link TsonReadContext} (in practice a {@code TsonDataStream}), never by
 * materializing a full {@code DataValue} tree first -- so a large document never has to be buffered
 * before binding can begin, memory held at any point is proportional to nesting depth. Problems are
 * reported through {@code ctx} using the same model the compiled readers use: a fail-fast context
 * throws {@link TsonReadException} at the first problem; a {@link TsonReadContext#collecting
 * collecting} context accumulates every independent problem into {@link TsonReadContext#diagnostics()}
 * and reads on. A {@code tson-bind} {@link DataBindException} thrown while narrowing a value or
 * invoking a constructor is caught and re-reported through {@code ctx} too, so a caller sees one
 * uniform error model regardless of which layer noticed the problem.
 *
 * <p>Atom binding first checks whether the value carries a type-ref. If it does, {@link
 * BuiltinTypeVocabulary} must resolve it (§5) -- an unrecognized type-ref is a binding error here,
 * not silently ignored, even though the Class 1 parsing step underneath (§5.1) is required to preserve
 * an unrecognized annotation as an uninterpreted marker: that rule is about passive preservation
 * during parsing, not about what an application actively binding to a caller-declared Java type should
 * do with a marker it can't interpret (see {@code SPEC-FEEDBACK.md} #7). With no type-ref, binding
 * falls through to plain untyped resolution: {@link BaseTypeResolver} (which of null/boolean/number/
 * string) then {@link AtomBinder} (that shape into whatever concrete Java type the target field
 * declares). Both paths share the same final narrowing step ({@code NumberNarrowing}), so a plain
 * {@code 42} and a {@code !uint8 42} bind identically regardless of which path found them.
 *
 * <p><b>No positional form and no schema-composed defaults</b> -- both are schema-layer concepts a
 * schemaless, class-driven bind has no equivalent for; a record must be written braced, and an absent
 * required field is a {@code FIELD_REQUIRED} problem. Duplicate field names resolve last-value-wins
 * (§2.5) by overwriting as they stream, the same as {@link TsonValueReader}'s own record readers.
 */
public final class TsonObjectReader {

    private final DataBindContext dataBindContext;
    private final TsonCompiledSchemaRegistry bind;

    public TsonObjectReader(TsonCompiledMetaRegistry core, DataBindContext dataBindContext) {
        this.dataBindContext = dataBindContext;
        this.bind = TsonCompiledSchemaRegistry.bind(core, dataBindContext);
    }

    public TsonObjectReader(DataBindContext context) {
        this(new TsonCompiledMetaRegistry(context), context);
    }

    public TsonObjectReader() {
        this(TsonAtomContext.defaultContext());
    }

    // ── Entry points ─────────────────────────────────────────────────────

    /** Reads {@code source}'s own root value, fail-fast, into {@code targetClass} -- throws {@link TsonReadException} on the first problem. */
    public <T> T read(String source, Class<T> targetClass) {
        return read(new TsonDataStream(source), targetClass);
    }

    /** The streaming counterpart to {@link #read(String, Class)} -- binds {@code source}'s own bytes (UTF-8) genuinely, never buffering the whole document into a {@code String} first; {@code source} is not closed here. */
    public <T> T read(InputStream source, Class<T> targetClass) {
        return read(new TsonDataStream(source), targetClass);
    }

    /**
     * Binds one value at {@code ctx}'s current position into {@code targetClass}. The general form,
     * for a caller managing their own {@link TsonReadContext} -- e.g. a {@link
     * TsonReadContext#collecting collecting} context to gather every problem in one pass rather than
     * throwing on the first. Does not check for document framing (no trailing-content check); use
     * {@link #read(String, Class)}/{@link #read(InputStream, Class)} for a whole document.
     */
    public <T> T read(TsonReadContext ctx, Class<T> type ) {
        return readStream(ctx, type, false);
    }

    public <T> T readWithoutSchema(TsonReadContext ctx, Class<T> type ) {
        return readStream(ctx, type, true);
    }

    private <T> T read(TsonDataStream stream, Class<T> type) {
        return readStream(TsonReadContext.throwing(stream), type, false);
    }

    private <T> T readStream(TsonReadContext ctx, Class<T> type, boolean ignoreSchema) {
        Objects.requireNonNull(type, "type");
        DocumentStart start = (DocumentStart) ctx.next();
        if (ignoreSchema || start.schema().isEmpty()) {
            return new SchemalessObjectReader(dataBindContext).read(ctx, type);
        }
        RootReader root = select(start.schema().get(), ctx, bind);
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

    /** The bound Java class the schema type {@code typeRefName} maps to, or {@code null} if it isn't name-bound (e.g. an atom root) -- so the before-read type check falls back to a post-read cast. */
    private Class<?> boundClass(String typeRefName) {
        try {
            return dataBindContext.getDescriptor(typeRefName).typeClass();
        } catch (DataBindException e) {
            return null;
        }
    }

    private record RootReader(TsonValueReader<?> reader, String typeName) {
    }


    private RootReader select(String schemaUri, TsonReadContext ctx, TsonCompiledSchemaRegistry registry) {
        TsonCompiledSchema compiled;
        try {
            compiled = registry.get(schemaUri);
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

    private static Diagnostic problem(Diagnostic.Code code, String message) {
        return new Diagnostic("", code, message, "", "", Optional.empty(), Optional.empty());
    }
}
