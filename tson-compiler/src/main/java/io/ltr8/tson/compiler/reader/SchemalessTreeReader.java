package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonDataStream;
import io.ltr8.tson.compiler.TsonObjectReader;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.atom.AtomType;
import io.ltr8.tson.compiler.atom.AtomTypeException;
import io.ltr8.tson.compiler.atom.BuiltinTypeVocabulary;
import io.ltr8.tson.compiler.atom.ValueParser;
import io.ltr8.tson.compiler.stream.AbsentEvent;
import io.ltr8.tson.compiler.stream.ArrayEnd;
import io.ltr8.tson.compiler.stream.ArrayStart;
import io.ltr8.tson.compiler.stream.EmptyBraceEvent;
import io.ltr8.tson.compiler.stream.FieldName;
import io.ltr8.tson.compiler.stream.MapEnd;
import io.ltr8.tson.compiler.stream.MapStart;
import io.ltr8.tson.compiler.stream.RecordEnd;
import io.ltr8.tson.compiler.stream.RecordStart;
import io.ltr8.tson.compiler.stream.SchemaRef;
import io.ltr8.tson.compiler.stream.TokenEvent;
import io.ltr8.tson.compiler.stream.TsonEvent;
import io.ltr8.tson.tree.*;
import io.ltr8.tson.tree.TsonValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads a TSON data document into an immutable {@link TsonValue} tree with <b>no schema</b> -- the
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
 * <p>Schemaless, so: an array is always a {@link TsonArray} (the grammar has no array/tuple distinction --
 * only a schema-driven read produces a {@code TsonTuple}), and {@code {}} resolves to an empty {@link
 * TsonRecord} (§2.8 leaves this to the resolver; a tree with no schema picks record).
 *
 * <p><b>Type-refs are checked</b>, by {@link TypeRefCheck}'s rules: a built-in name must sit on a token and
 * that token must satisfy the atom, and with no target class to name (rule 2 is object-binding only) any
 * other name links to nothing and is {@code UNKNOWN_TYPE_REF}. {@link #preserving()} opts out of that last
 * rule for a caller who wants the wire back as authored -- reading the structure of a document whose
 * {@code !!schema} is deliberately being ignored, or round-tripping through {@code TsonTreeWriter}.
 *
 * <p><b>Every problem goes through {@code ctx.report}</b>, so the read's own {@code TsonDiagnosticsReceiver}
 * decides its fate exactly as it does for the schema-driven readers: fail-fast throws {@code
 * TsonReadException} at the first, a collector gathers them all and still hands back a tree. Reporting never
 * abandons the value -- the node is still built and its children are still read, so one pass finds
 * everything; a leaf whose atom rejected the token becomes a {@link TsonNull}, the placeholder {@code
 * AtomTreeReader} uses for the same situation.
 *
 * <p><b>Wire annotations are captured</b> onto each node's own {@code annotations()}, at every position §3.1
 * permits one: the root value, a record field's value, an array element, either side of a map entry (a
 * {@code TsonMap.Entry} key is a node, so an annotated key keeps its own), and recursively an annotation's
 * own value. A record's <em>field name</em> never carries any -- §2.5 forbids annotations before a field
 * name, so {@code TsonRecord.fields()} is keyed by a plain string, matching the grammar. An annotation's own
 * value is read by this same reader, so it is checked the same way the value it annotates is; nothing checks
 * the annotation's <em>name</em>, since with no governing schema there is no type to resolve it against,
 * which is [TSON-DATA] §3.1's Class 1 treatment (the schema-driven readers do resolve and validate them,
 * [TSON-SCHEMA] §6).
 */
public final class SchemalessTreeReader {

    /** Whether a type-ref that links to nothing is preserved silently rather than reported -- see {@link #preserving()}. */
    private final boolean preserveUnknownTypeRefs;

    /** Reports a type-ref that names no built-in type ({@link TypeRefCheck}'s rule 3). */
    public SchemalessTreeReader() {
        this(false);
    }

    private SchemalessTreeReader(boolean preserveUnknownTypeRefs) {
        this.preserveUnknownTypeRefs = preserveUnknownTypeRefs;
    }

    /**
     * A reader that keeps a type-ref naming no built-in type on the node without reporting it -- §5.1's
     * uninterpreted marker, carried all the way to the tree. For reading the wire form of a document whose
     * own {@code !!schema} defines those names but is deliberately not in scope.
     */
    public static SchemalessTreeReader preserving() {
        return new SchemalessTreeReader(true);
    }

    /**
     * Reads one value at {@code ctx}'s current position into a tree -- frame-free, for a caller managing
     * their own {@link TsonReadContext}. Whole-document framing (consuming the leading {@code
     * DocumentStart}) belongs to the facades that own a document, {@code TsonTreeReader}/{@code
     * TsonObjectReader}.
     */
    public TsonValue read(TsonReadContext ctx) {
        return readNode(ctx);
    }

    /** Reads one data-value: its leading annotations and optional type-ref (§2.3), then its core-value. */
    private TsonValue readNode(TsonReadContext ctx) {
        List<TsonAnnotation> annotations = AnnotationCapture.annotations(ctx, AnnotationTypes.UNVALIDATED, this);
        Optional<String> typeRef = EventSkip.typeRef(ctx);
        TsonEvent e = ctx.peek();
        Optional<AtomType<?>> atom = checkTypeRef(ctx, typeRef, e);
        return switch (e) {
            case RecordStart ignored -> readRecord(ctx, typeRef, annotations);
            case MapStart ignored -> readMap(ctx, typeRef, annotations);
            case ArrayStart ignored -> readArray(ctx, typeRef, annotations);
            case EmptyBraceEvent ignored -> {
                ctx.next();
                yield new TsonRecord(Map.of(), typeRef, annotations);
            }
            case AbsentEvent ignored -> {
                ctx.next();
                yield new TsonAbsent(typeRef, annotations);
            }
            case TokenEvent token -> {
                ctx.next();
                yield leaf(ctx, token, typeRef, atom, annotations);
            }
            default -> throw new IllegalStateException("unexpected event where a value was expected: " + e);
        };
    }

    /**
     * Applies {@link TypeRefCheck}'s rules to this value's own type-ref, returning the built-in atom that
     * should decode it -- empty when there is no type-ref, when the name isn't a built-in, or when a built-in
     * name sits on a container (all three reported unless preserved, and all three then read structurally,
     * so a collecting read still descends into whatever was actually written).
     */
    private Optional<AtomType<?>> checkTypeRef(TsonReadContext ctx, Optional<String> typeRef, TsonEvent core) {
        if (typeRef.isEmpty()) {
            return Optional.empty();
        }
        String name = typeRef.get();
        Optional<AtomType<?>> atom = BuiltinTypeVocabulary.lookup(name);
        if (atom.isEmpty()) {
            if (!preserveUnknownTypeRefs) {
                TypeRefCheck.unknown(ctx, name);
            }
            return Optional.empty();
        }
        if (!(core instanceof TokenEvent)) {
            TypeRefCheck.notScalar(ctx, name, core);
            return Optional.empty();
        }
        return atom;
    }

    private TsonRecord readRecord(TsonReadContext ctx, Optional<String> typeRef, List<TsonAnnotation> annotations) {
        ctx.next(); // RecordStart
        Map<String, TsonValue> fields = new LinkedHashMap<>();
        while (!(ctx.peek() instanceof RecordEnd)) {
            FieldName fieldName = (FieldName) ctx.next();
            if (ctx.peek() instanceof SchemaRef) {
                ctx.next();
            }
            fields.put(fieldName.name(), readNode(ctx.field(fieldName.name())));
        }
        ctx.next(); // RecordEnd
        return new TsonRecord(fields, typeRef, annotations);
    }

    private TsonArray readArray(TsonReadContext ctx, Optional<String> typeRef, List<TsonAnnotation> annotations) {
        ctx.next(); // ArrayStart
        List<TsonValue> elements = new ArrayList<>();
        while (!(ctx.peek() instanceof ArrayEnd)) {
            if (ctx.peek() instanceof SchemaRef) {
                ctx.next();
            }
            elements.add(readNode(ctx.index(elements.size())));
        }
        ctx.next(); // ArrayEnd
        return new TsonArray(elements, typeRef, annotations);
    }

    /**
     * A map entry's <em>value</em> is scoped one segment deeper, keyed by the key's own text (§2.6 allows any
     * data-value as a key; one that isn't a plain scalar has no useful segment and becomes {@code ?}). The
     * key itself is read at the map's own path -- it is not inside the entry it identifies, and it has to be
     * read before its segment can be known.
     */
    private TsonMap readMap(TsonReadContext ctx, Optional<String> typeRef, List<TsonAnnotation> annotations) {
        ctx.next(); // MapStart
        List<TsonMap.Entry> entries = new ArrayList<>();
        while (!(ctx.peek() instanceof MapEnd)) {
            TsonValue key = readNode(ctx);
            ctx.next(); // MapArrow
            if (ctx.peek() instanceof SchemaRef) {
                ctx.next();
            }
            entries.add(new TsonMap.Entry(key, readNode(ctx.field(keySegment(key)))));
        }
        ctx.next(); // MapEnd
        return new TsonMap(entries, typeRef, annotations);
    }

    /** A token leaf, decoded by the built-in atom {@link #checkTypeRef} resolved, else by §4 base resolution. */
    private TsonValue leaf(TsonReadContext ctx, TokenEvent token, Optional<String> typeRef,
                           Optional<AtomType<?>> atom, List<TsonAnnotation> annotations) {
        TokenValue tokenValue = new TokenValue(token.text(), token.form());
        Object value;
        if (atom.isPresent()) {
            try {
                value = atom.get().read(tokenValue);
            } catch (AtomTypeException e) {
                TypeRefCheck.violation(ctx, typeRef.orElseThrow(), e, token.text());
                return new TsonNull(typeRef, annotations);
            }
        } else {
            value = ValueParser.INSTANCE.read(tokenValue);
        }
        return value == null ? new TsonNull(typeRef, annotations) : new TsonAtom(value, typeRef, annotations);
    }

    /** A map key's own path segment: its scalar text, or {@code ?} for a key with no single text form. */
    private static String keySegment(TsonValue key) {
        return key instanceof TsonAtom atom ? String.valueOf(atom.value()) : "?";
    }
}
