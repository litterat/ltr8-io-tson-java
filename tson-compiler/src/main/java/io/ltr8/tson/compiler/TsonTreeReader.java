package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.reader.SchemalessTreeReader;
import io.ltr8.tson.compiler.stream.DocumentEnd;
import io.ltr8.tson.compiler.stream.DocumentStart;
import io.ltr8.tson.compiler.stream.TsonEvent;
import io.ltr8.tson.compiler.stream.TypeRef;
import io.ltr8.tson.tree.TsonNode;

import java.io.InputStream;
import java.util.Optional;

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
 * as events arrive without an intermediate {@code DataValue} AST. This is a <i>read</i>, fail-fast: a
 * malformed document or an out-of-range typed value throws {@link TsonReadException} at the first problem;
 * a caller wanting every problem at once uses {@link Tson#validate}.
 *
 * <p>Wire annotations are captured on the <b>schemaless</b> path only -- a node read that way carries its
 * own {@code annotations()} (§3.1). A schema-driven read leaves them empty for now, so a document with a
 * {@code !!schema} loses them unless read through {@link #readWithoutSchema}.
 */
public final class TsonTreeReader {

    private final SchemalessTreeReader schemaless = new SchemalessTreeReader();

    /** The tree-mode compiled-schema registry a schema-aware reader validates through, or {@code null} for a schemaless reader (any {@code !!schema} is then ignored). */
    private final TsonCompiledSchemaRegistry tree;

    /** Schema-aware -- validates a self-describing document against its {@code !!schema}, resolved through {@code core}'s own source. Used by {@link Tson#treeReader()}. */
    public TsonTreeReader(TsonCompiledMetaRegistry core) {
        this.tree = TsonCompiledSchemaRegistry.tree(core);
    }

    /** Schemaless (Class 1) -- reads the wire structure into a tree, ignoring any {@code !!schema} the document declares. */
    public TsonTreeReader() {
        this.tree = null;
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
        TsonReadContext ctx = TsonReadContext.throwing(stream);
        DocumentStart start = (DocumentStart) ctx.next();
        TsonNode result = (ignoreSchema || tree == null || start.schema().isEmpty())
                ? schemaless.read(ctx)
                : readAgainstSchema(start.schema().get(), ctx);
        TsonEvent trailing = ctx.next();
        if (!(trailing instanceof DocumentEnd)) {
            throw new IllegalStateException("unexpected trailing event after the document's value: " + trailing);
        }
        return result;
    }

    private TsonNode readAgainstSchema(String schemaUri, TsonReadContext ctx) {
        TsonCompiledSchema compiled;
        try {
            compiled = tree.get(schemaUri);
        } catch (RuntimeException e) {
            throw new TsonReadException(problem(Diagnostic.Code.SCHEMA_ERROR, e.getMessage()));
        }
        if (!(ctx.peek() instanceof TypeRef typeRef)) {
            throw new TsonReadException(problem(Diagnostic.Code.VALIDATION_ERROR,
                    "data declares a !!schema but has no root type-ref (e.g. `!person`) to select a type"));
        }
        TsonValueReader<?> reader;
        try {
            reader = compiled.get(typeRef.name());
        } catch (RuntimeException e) {
            throw new TsonReadException(problem(Diagnostic.Code.UNKNOWN_TYPE, e.getMessage()));
        }
        return (TsonNode) reader.read(ctx);
    }

    private static Diagnostic problem(Diagnostic.Code code, String message) {
        return new Diagnostic("", code, message, "", "", Optional.empty(), Optional.empty());
    }
}
