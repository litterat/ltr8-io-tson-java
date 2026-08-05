package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonDataStream;
import io.ltr8.tson.compiler.TsonObjectReader;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.atom.AtomType;
import io.ltr8.tson.compiler.atom.BuiltinTypeVocabulary;
import io.ltr8.tson.compiler.atom.ValueParser;
import io.ltr8.tson.compiler.stream.AbsentEvent;
import io.ltr8.tson.compiler.stream.ArrayEnd;
import io.ltr8.tson.compiler.stream.ArrayStart;
import io.ltr8.tson.compiler.stream.DocumentEnd;
import io.ltr8.tson.compiler.stream.EmptyBraceEvent;
import io.ltr8.tson.compiler.stream.FieldName;
import io.ltr8.tson.compiler.stream.MapEnd;
import io.ltr8.tson.compiler.stream.MapStart;
import io.ltr8.tson.compiler.stream.RecordEnd;
import io.ltr8.tson.compiler.stream.RecordStart;
import io.ltr8.tson.compiler.stream.SchemaRef;
import io.ltr8.tson.compiler.stream.TokenEvent;
import io.ltr8.tson.compiler.stream.TsonEvent;
import io.ltr8.tson.tree.AbsentNode;
import io.ltr8.tson.tree.ArrayNode;
import io.ltr8.tson.tree.AtomNode;
import io.ltr8.tson.tree.MapNode;
import io.ltr8.tson.tree.NullNode;
import io.ltr8.tson.tree.RecordNode;
import io.ltr8.tson.tree.TsonNode;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads a TSON data document into an immutable {@link TsonNode} tree with <b>no schema</b> -- the
 * schemaless (Class 1) tree-producing peer of {@link TsonObjectReader} (which produces Java objects). Like
 * Jackson's {@code readTree}: the wire structure is the source of truth. Leaves are typed by §4 base
 * resolution ({@code null}/{@code Boolean}/{@code BigInteger}/{@code BigDecimal}/{@code Double}/{@code
 * String}), or by the built-in vocabulary when a leaf carries a type-ref for one (e.g. {@code !uuid},
 * {@code !date}); a container carries its own wire type-ref (e.g. {@code !person}) when present.
 *
 * <p><b>Streams the event source directly</b> (a {@link TsonDataStream}), the same way {@link
 * TsonObjectReader} does -- building nodes as events arrive, never materializing an intermediate {@code
 * DataValue} AST. Since a tree materializes the whole document anyway, the point isn't a bounded working
 * set (as it is for object binding), but avoiding a second representation and staying consistent with the
 * rest of the read stack.
 *
 * <p>Schemaless, so: an array is always an {@link ArrayNode} (the grammar has no array/tuple distinction --
 * only a schema-driven read produces a {@code TupleNode}), and {@code {}} resolves to an empty {@link
 * RecordNode} (§2.8 leaves this to the resolver; a tree with no schema picks record). This is a <i>read</i>,
 * not a collecting validation, so malformed syntax and an out-of-range built-in-typed value ({@code !uuid
 * nope}) throw; a caller wanting diagnostics uses {@code Tson.validate}. Wire annotations are not yet
 * captured (a node's {@code annotations()} is empty), matching the schema-driven readers.
 */
public final class SchemalessTreeReader {

    public SchemalessTreeReader() {
    }

    /** TSON text straight to a {@link TsonNode} tree. */
    public TsonNode read(String source) {
        return readDocument(TsonReadContext.throwing(source));
    }

    /** As {@link #read(String)}, from a stream (read incrementally off a {@link TsonDataStream}). */
    public TsonNode read(InputStream source) {
        return readDocument(TsonReadContext.throwing(source));
    }

    /**
     * Reads one value at {@code ctx}'s current position into a tree -- frame-free (no trailing-content
     * check), for a caller managing their own {@link TsonReadContext}; the {@code String}/{@code
     * InputStream} entry points wrap this with whole-document framing.
     */
    public TsonNode read(TsonReadContext ctx) {
        return readNode(ctx);
    }

    private TsonNode readDocument(TsonReadContext ctx) {
        TsonNode root = readNode(ctx);
        TsonEvent trailing = ctx.next();
        if (!(trailing instanceof DocumentEnd)) {
            throw new IllegalStateException("unexpected trailing event after the document's value: " + trailing);
        }
        return root;
    }

    /** Reads one data-value: its leading annotations (discarded) and optional type-ref, then its core-value. */
    private TsonNode readNode(TsonReadContext ctx) {
        Optional<String> typeRef = EventSkip.annotationsAndTypeRef(ctx);
        TsonEvent e = ctx.peek();
        return switch (e) {
            case RecordStart ignored -> readRecord(ctx, typeRef);
            case MapStart ignored -> readMap(ctx, typeRef);
            case ArrayStart ignored -> readArray(ctx, typeRef);
            case EmptyBraceEvent ignored -> {
                ctx.next();
                yield new RecordNode(Map.of(), typeRef, List.of());
            }
            case AbsentEvent ignored -> {
                ctx.next();
                yield new AbsentNode(typeRef, List.of());
            }
            case TokenEvent token -> {
                ctx.next();
                yield leaf(token, typeRef);
            }
            default -> throw new IllegalStateException("unexpected event where a value was expected: " + e);
        };
    }

    private RecordNode readRecord(TsonReadContext ctx, Optional<String> typeRef) {
        ctx.next(); // RecordStart
        Map<String, TsonNode> fields = new LinkedHashMap<>();
        while (!(ctx.peek() instanceof RecordEnd)) {
            FieldName fieldName = (FieldName) ctx.next();
            if (ctx.peek() instanceof SchemaRef) {
                ctx.next();
            }
            fields.put(fieldName.name(), readNode(ctx));
        }
        ctx.next(); // RecordEnd
        return new RecordNode(fields, typeRef, List.of());
    }

    private ArrayNode readArray(TsonReadContext ctx, Optional<String> typeRef) {
        ctx.next(); // ArrayStart
        List<TsonNode> elements = new ArrayList<>();
        while (!(ctx.peek() instanceof ArrayEnd)) {
            if (ctx.peek() instanceof SchemaRef) {
                ctx.next();
            }
            elements.add(readNode(ctx));
        }
        ctx.next(); // ArrayEnd
        return new ArrayNode(elements, typeRef, List.of());
    }

    private MapNode readMap(TsonReadContext ctx, Optional<String> typeRef) {
        ctx.next(); // MapStart
        List<MapNode.Entry> entries = new ArrayList<>();
        while (!(ctx.peek() instanceof MapEnd)) {
            TsonNode key = readNode(ctx);
            ctx.next(); // MapArrow
            if (ctx.peek() instanceof SchemaRef) {
                ctx.next();
            }
            entries.add(new MapNode.Entry(key, readNode(ctx)));
        }
        ctx.next(); // MapEnd
        return new MapNode(entries, typeRef, List.of());
    }

    /** A token leaf: resolved via the built-in vocabulary if it carries a type-ref for one, else by base resolution. */
    private TsonNode leaf(TokenEvent token, Optional<String> typeRef) {
        TokenValue tokenValue = new TokenValue(token.text(), token.form());
        Object value = typeRef.flatMap(BuiltinTypeVocabulary::lookup)
                .<Object>map(atom -> ((AtomType<?>) atom).read(tokenValue))
                .orElseGet(() -> ValueParser.INSTANCE.read(tokenValue));
        return value == null ? new NullNode(typeRef, List.of()) : new AtomNode(value, typeRef, List.of());
    }
}
