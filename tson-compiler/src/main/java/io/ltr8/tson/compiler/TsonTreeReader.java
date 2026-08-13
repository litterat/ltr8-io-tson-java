package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.reader.EventSkip;
import io.ltr8.tson.compiler.reader.SchemalessTreeReader;
import io.ltr8.tson.compiler.stream.DocumentEnd;
import io.ltr8.tson.compiler.stream.DocumentStart;
import io.ltr8.tson.compiler.stream.TsonEvent;
import io.ltr8.tson.compiler.stream.TypeRef;
import io.ltr8.tson.tree.TsonNode;

import java.io.InputStream;

/**
 * Reads a TSON data document into an immutable {@link TsonNode} tree -- the tree-producing read-side front
 * door, the inverse of {@link TsonTreeWriter} and the tree-shaped peer of {@link TsonObjectReader} (which
 * produces bound Java objects). Like Jackson's {@code readTree}.
 *
 * <p><b>Two modes, fixed at construction.</b> A reader from {@link Tson#treeReader()} (the {@code
 * (TsonCompiledMetaRegistry)} constructor) is <i>schema-aware</i>: a document that declares a {@code
 * !!schema} is validated against it as the tree is built -- the schema resolves through that environment's
 * own source and the document's root type-ref (e.g. {@code !person}) selects the type, so the tree is
 * structure-preserving (record vs map, array vs tuple) with the schema's own leaf types. A reader built
 * standalone ({@link #TsonTreeReader()}) is <i>schemaless</i> (Class 1): any {@code !!schema} the document
 * declares is ignored and the wire is the source of truth -- an array is always an {@link
 * io.ltr8.tson.tree.ArrayNode} (only a schema-driven read produces a tuple), {@code {}} is an empty {@link
 * io.ltr8.tson.tree.RecordNode}, and leaves are typed by §4 base resolution or the built-in vocabulary.
 * When no {@code !!schema} is present the two modes behave identically. {@link #readWithoutSchema} forces
 * the schemaless path on a schema-aware reader.
 *
 * <p>Either way the tree is streamed off the event source ({@link TsonDataStream}) directly, building nodes
 * as events arrive without an intermediate {@code DataValue} AST. A read is fail-fast by default: a malformed
 * document or an out-of-range typed value throws {@link TsonReadException} at the first problem. {@link
 * #withDiagnostics} swaps that for any other {@link TsonDiagnosticsReceiver} -- a collector gathers every
 * problem in one pass and still hands back the (possibly partial) tree, in schema-aware and schemaless mode
 * alike.
 *
 * <p>Wire annotations are captured on the <b>schemaless</b> path only -- a node read that way carries its
 * own {@code annotations()} (§3.1). A schema-driven read leaves them empty for now, so a document with a
 * {@code !!schema} loses them unless read through {@link #readWithoutSchema}.
 */
public final class TsonTreeReader {

    private final SchemalessTreeReader schemaless = new SchemalessTreeReader();

    /** The tree-mode compiled-schema registry a schema-aware reader validates through, or {@code null} for a schemaless reader (any {@code !!schema} is then ignored). */
    private final TsonCompiledSchemaRegistry tree;

    /** Where this reader's reads report their problems -- fail-fast unless {@link #withDiagnostics} said otherwise. */
    private final TsonDiagnosticsReceiver receiver;

    /** The schema {@link #readAs} validates against, or {@code null} until {@link #withSchema} names one. */
    private final String schemaUri;

    /** Schema-aware -- validates a self-describing document against its {@code !!schema}, resolved through {@code core}'s own source. Used by {@link Tson#treeReader()}. */
    public TsonTreeReader(TsonCompiledMetaRegistry core) {
        this(TsonCompiledSchemaRegistry.tree(core), TsonDiagnosticsReceiver.throwing(), null);
    }

    /** Schemaless (Class 1) -- reads the wire structure into a tree, ignoring any {@code !!schema} the document declares. */
    public TsonTreeReader() {
        this(null, TsonDiagnosticsReceiver.throwing(), null);
    }

    /** Shares {@code tree} rather than rebuilding it -- a derived reader must keep the original's compiled-schema cache, not start an empty one. */
    private TsonTreeReader(TsonCompiledSchemaRegistry tree, TsonDiagnosticsReceiver receiver, String schemaUri) {
        this.tree = tree;
        this.receiver = receiver;
        this.schemaUri = schemaUri;
    }

    /**
     * This reader bound to the schema {@code schemaUri} names, for {@link #readAs} -- a new reader, leaving
     * this one unchanged, sharing its compiled-schema registry. The schema is resolved through the same
     * source and cache a self-describing document's own {@code !!schema} goes through, so it must already be
     * registered (e.g. via {@link Tson#resolve}) or be servable by the configured {@code TsonSchemaSource}.
     */
    public TsonTreeReader withSchema(String schemaUri) {
        if (tree == null) {
            throw new IllegalStateException("a schemaless TsonTreeReader has no schema environment to resolve '"
                    + schemaUri + "' through -- obtain one from Tson.treeReader()");
        }
        return new TsonTreeReader(tree, receiver, schemaUri);
    }

    /**
     * This reader, reporting through {@code receiver} instead of throwing at the first problem -- a new reader,
     * leaving this one unchanged, sharing its compiled-schema registry:
     *
     * <pre>{@code
     * var problems = TsonDiagnosticsReceiver.collecting();
     * TsonNode tree = tson.treeReader().withDiagnostics(problems).read(source);
     * problems.diagnostics();      // every problem, alongside a possibly-partial tree
     * }</pre>
     *
     * <p>Applies to the whole-document entry points only. {@link #read(TsonReadContext)} takes a context that
     * carries its own receiver, and that one wins.
     */
    public TsonTreeReader withDiagnostics(TsonDiagnosticsReceiver receiver) {
        return new TsonTreeReader(tree, receiver, schemaUri);
    }

    // ── Whole-document entry points ──────────────────────────────────────

    /** Reads {@code source}'s whole document into a {@link TsonNode} tree, fail-fast -- validated against its {@code !!schema} if this reader is schema-aware and the document declares one, schemaless otherwise. */
    public TsonNode read(String source) {
        return readDocument(new TsonDataStream(source), false);
    }

    /** {@link #read(String)} straight off a stream -- reads {@code source}'s bytes (UTF-8) incrementally, never buffering the whole document into a {@code String} first; {@code source} is not closed here. */
    public TsonNode read(InputStream source) {
        return readDocument(new TsonDataStream(source), false);
    }

    /** Like {@link #read(String)} but always schemaless -- reads the wire structure, even when the document declares a {@code !!schema}. (A schemaless reader's {@link #read} already does this.) */
    public TsonNode readWithoutSchema(String source) {
        return readDocument(new TsonDataStream(source), true);
    }

    /** {@link #readWithoutSchema(String)} straight off a stream. */
    public TsonNode readWithoutSchema(InputStream source) {
        return readDocument(new TsonDataStream(source), true);
    }

    /**
     * Reads {@code source} as {@code typeName}, declared by the schema {@link #withSchema} named -- for data
     * that isn't self-describing, where you hold the schema out of band. The caller supplies what a {@code
     * !!schema} plus a root type-ref would otherwise say, and validation is identical either way; a root
     * type-ref the data does carry is read as part of the value, not used to select the type.
     */
    public TsonNode readAs(String source, String typeName) {
        return readDocumentAs(new TsonDataStream(source), typeName);
    }

    /** {@link #readAs(String, String)} straight off a stream. */
    public TsonNode readAs(InputStream source, String typeName) {
        return readDocumentAs(new TsonDataStream(source), typeName);
    }

    /**
     * Reads one value at {@code ctx}'s current position into a tree -- the low-level form for a caller
     * managing their own {@link TsonReadContext}. Always schemaless and frame-free: it neither inspects a
     * {@code !!schema} (an arbitrary position carries no document framing to hold one) nor checks for
     * trailing content; use the {@code String}/{@code InputStream} entry points for a whole document.
     */
    public TsonNode read(TsonReadContext ctx) {
        return schemaless.read(ctx);
    }

    // ── Internals ────────────────────────────────────────────────────────

    private TsonNode readDocument(TsonDataStream stream, boolean ignoreSchema) {
        TsonReadContext ctx = TsonReadContext.of(stream, receiver);
        DocumentStart start = (DocumentStart) ctx.next();
        TsonNode result = (ignoreSchema || tree == null || start.schema().isEmpty())
                ? schemaless.read(ctx)
                : readAgainstSchema(start.schema().get(), ctx, null);
        requireDocumentEnd(ctx);
        return result;
    }

    private TsonNode readDocumentAs(TsonDataStream stream, String typeName) {
        if (schemaUri == null) {
            throw new IllegalStateException("readAs needs a schema -- call withSchema(uri) first");
        }
        TsonReadContext ctx = TsonReadContext.of(stream, receiver);
        ctx.next(); // DocumentStart -- any !!schema it declares is overridden by withSchema
        TsonNode result = readAgainstSchema(schemaUri, ctx, typeName);
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
     * Reads the root value against {@code schemaUri}'s type -- {@code typeName} when {@link #readAs} supplied
     * one, else the document's own root type-ref.
     *
     * <p>A problem reaching the schema is reported through {@code ctx} like any other, so a fail-fast reader
     * still throws while a collecting one gets it as a {@link Diagnostic} -- the same promise {@link
     * Tson#validate} makes. The document's own value is then skipped so the stream still lands on {@code
     * DocumentEnd} and {@link #requireDocumentEnd} stays meaningful.
     */
    private TsonNode readAgainstSchema(String schemaUri, TsonReadContext ctx, String typeName) {
        TsonCompiledSchema compiled;
        try {
            compiled = tree.get(schemaUri);
        } catch (RuntimeException e) {
            return abandon(ctx, Diagnostic.Code.SCHEMA_ERROR, e.getMessage());
        }
        String name = typeName;
        if (name == null) {
            if (!(ctx.peek() instanceof TypeRef typeRef)) {
                return abandon(ctx, Diagnostic.Code.VALIDATION_ERROR,
                        "data declares a !!schema but has no root type-ref (e.g. `!person`) to select a type");
            }
            name = typeRef.name();
        }
        TsonValueReader<?> reader;
        try {
            reader = compiled.get(name);
        } catch (RuntimeException e) {
            return abandon(ctx, Diagnostic.Code.UNKNOWN_TYPE, e.getMessage());
        }
        return (TsonNode) reader.read(ctx);
    }

    /** Reports {@code code}/{@code message}, discards the root value, and yields no tree -- see {@link #readAgainstSchema}. */
    private static TsonNode abandon(TsonReadContext ctx, Diagnostic.Code code, String message) {
        ctx.report(code, message, "", "");
        EventSkip.dataValue(ctx);
        return null;
    }
}
