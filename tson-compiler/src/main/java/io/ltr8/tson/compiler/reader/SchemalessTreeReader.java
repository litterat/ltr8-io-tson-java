package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonDataStream;
import io.ltr8.tson.compiler.TsonObjectReader;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.atom.AtomType;
import io.ltr8.tson.compiler.atom.AtomTypeException;
import io.ltr8.tson.compiler.atom.BuiltinTypeVocabulary;
import io.ltr8.tson.compiler.atom.ValueParser;
import io.ltr8.tson.compiler.lexer.ConfusableNames;
import io.ltr8.tson.compiler.stream.AbsentEvent;
import io.ltr8.tson.compiler.stream.ArrayEnd;
import io.ltr8.tson.compiler.stream.ArrayStart;
import io.ltr8.tson.compiler.stream.EmptyBraceEvent;
import io.ltr8.tson.compiler.stream.FieldName;
import io.ltr8.tson.compiler.stream.MapEnd;
import io.ltr8.tson.compiler.stream.MapStart;
import io.ltr8.tson.compiler.stream.RecordEnd;
import io.ltr8.tson.compiler.stream.RecordStart;
import io.ltr8.tson.compiler.stream.TokenEvent;
import io.ltr8.tson.compiler.stream.TsonEvent;
import io.ltr8.tson.tree.*;
import io.ltr8.tson.tree.TsonValue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads a TSON data document into an immutable {@link TsonValue} tree with <b>no schema</b> -- the
 * schemaless (Class 1) tree-producing peer of {@link TsonObjectReader} (which produces Java objects). Like
 * Jackson's {@code readTree}: the wire structure is the source of truth. Leaves are typed by §4 base
 * resolution ({@code Boolean}/{@code BigInteger}/{@code BigDecimal}/{@code Double}/{@code String}), or by the
 * built-in vocabulary when a leaf carries a type-ref for one (e.g. {@code !uuid},
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
 * everything; a leaf whose atom rejected the token becomes a {@link TsonAbsent}, the placeholder {@code
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

    /**
     * A field name stated twice is reported ({@code DUPLICATE_FIELD}, §2.5) and its value still overwrites
     * the earlier one -- the last-value-wins recovery, which the {@code LinkedHashMap} applies anyway.
     * §2.5's MUST NOT is Part 1's and needs no schema to see, so it holds on this path exactly as it does
     * under a compiled one; a document whose verdict changed depending on whether a schema was in scope
     * would be an interoperability failure the rule exists to prevent.
     */
    private TsonRecord readRecord(TsonReadContext ctx, Optional<String> typeRef, List<TsonAnnotation> annotations) {
        ctx.next(); // RecordStart
        Map<String, TsonValue> fields = new LinkedHashMap<>();
        while (!(ctx.peek() instanceof RecordEnd)) {
            FieldName fieldName = (FieldName) ctx.next();
            ScopePush.refuseSchemaless(ctx);
            if (fields.containsKey(fieldName.name())) {
                ctx.field(fieldName.name()).report(Diagnostic.Code.DUPLICATE_FIELD,
                        "duplicate field '" + fieldName.name() + "' -- a record states each field at most once "
                                + "(§2.5), and the repeat states a value for nothing",
                        "each field stated once", "'" + fieldName.name() + "' stated again");
            }
            fields.put(fieldName.name(), readNode(ctx.field(fieldName.name())));
        }
        ctx.next(); // RecordEnd
        reportConfusableFields(ctx, fields.keySet());
        return new TsonRecord(fields, typeRef, annotations);
    }

    private TsonArray readArray(TsonReadContext ctx, Optional<String> typeRef, List<TsonAnnotation> annotations) {
        ctx.next(); // ArrayStart
        List<TsonValue> elements = new ArrayList<>();
        while (!(ctx.peek() instanceof ArrayEnd)) {
            ScopePush.refuseSchemaless(ctx);
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
        Set<Object> seen = new HashSet<>();
        while (!(ctx.peek() instanceof MapEnd)) {
            TsonValue key = readNode(ctx);
            if (key instanceof TsonAbsent) {
                // §2.9, and a resolver-layer constraint rather than a grammar one: the map-entry
                // production accepts any data-value in key position, so no tier below this one can
                // refuse it. The entry is still kept -- tree mode keeps everything it built -- and the
                // key is left out of `seen`, since a second `_` is this same problem again and not a
                // duplicate of a key the document meaningfully stated.
                ctx.report(Diagnostic.Code.TYPE_MISMATCH,
                        "the absent sentinel '_' must not appear as a map key (§2.9)",
                        "a real map key, never the absent sentinel '_'", "_");
            } else if (!seen.add(keyIdentity(key))) {
                // §2.6, the map half of readRecord's rule.
                ctx.report(Diagnostic.Code.DUPLICATE_MAP_KEY,
                        "duplicate key '" + keySegment(key) + "' -- a map states each key at most once (§2.6), "
                                + "and the repeat states an entry for nothing",
                        "each key stated once", "'" + keySegment(key) + "' stated again");
            }
            ctx.next(); // MapArrow
            ScopePush.refuseSchemaless(ctx);
            entries.add(new TsonMap.Entry(key, readNode(ctx.field(keySegment(key)))));
        }
        ctx.next(); // MapEnd
        return new TsonMap(entries, typeRef, annotations);
    }

    /**
     * A token leaf, decoded by the built-in atom {@link #checkTypeRef} resolved, else by §4 base resolution.
     *
     * <p>A token is always a value: §4 resolves every one of them to boolean, number or string, {@code null}
     * included, which is the string {@code null}. The one no-value outcome here is a token the atom rejected,
     * kept as a {@link TsonAbsent} placeholder -- reporting never abandons the surrounding value, and the
     * diagnostic rather than the placeholder carries what went wrong. Absence proper is {@code _}, which is
     * never a token and reaches the tree through its own event.
     */
    private TsonValue leaf(TsonReadContext ctx, TokenEvent token, Optional<String> typeRef,
                           Optional<AtomType<?>> atom, List<TsonAnnotation> annotations) {
        TokenValue tokenValue = new TokenValue(token.text(), token.form());
        Object value;
        if (atom.isPresent()) {
            try {
                value = atom.get().read(tokenValue);
            } catch (AtomTypeException e) {
                TypeRefCheck.violation(ctx, typeRef.orElseThrow(), e, token.text());
                return new TsonAbsent(typeRef, annotations);
            }
        } else {
            value = ValueParser.INSTANCE.read(tokenValue);
        }
        return new TsonAtom(value, typeRef, annotations);
    }

    /** A map key's own path segment: its scalar text, or {@code ?} for a key with no single text form. */
    private static String keySegment(TsonValue key) {
        return key instanceof TsonAtom atom ? String.valueOf(atom.value()) : "?";
    }

    /**
     * [TSON-DATA] §8.2's name hygiene over a record's own field set -- the one scope it names at the data
     * layer. A Class 1
     * record is the one naming scope with no declaration behind it: under a schema the check runs once over
     * the declared names and the data conforms by construction, but a schemaless document's fields are named
     * only here, so this is where two names a reader cannot tell apart have to be caught.
     *
     * <p>Reported after the record is read, not as each field arrives: the relation is over the whole set,
     * and a collision is a property of the pair rather than of the second name's position. Duplicates are
     * already reported per occurrence above; this is the different rule that two <em>distinct</em> names read
     * alike.
     */
    private static void reportConfusableFields(TsonReadContext ctx, Set<String> fieldNames) {
        ConfusableNames.firstCollision(fieldNames).ifPresent(collision ->
                ctx.field(collision.second()).report(Diagnostic.Code.CONFUSABLE_NAMES,
                        "this record " + collision.describe(),
                        "field names a reader can tell apart", "'" + collision.second() + "'"));
    }

    /**
     * What {@link #readMap}'s duplicate check compares: a key's structure and decoded values, with every
     * node's type-ref and annotations stripped. Neither is part of the key §2.6 compares -- it asks for
     * "the same NFC-normalized string after escape processing" for a scalar and "the same structure with
     * textually identical elements at every position" for a compound one, and a leading {@code !text} or
     * {@code @doc} is in neither. Comparing whole {@link TsonValue} nodes instead would read {@code !text
     * a} and {@code a} as two keys, which §2.6 says they are not.
     *
     * <p>Equating on the <em>decoded</em> value rather than the source text is what §2.6 asks of a reader
     * that decodes: textual identity is the parser's minimum, "a processor that decodes values compares
     * decoded values" is the layer above it, and a declared key type ([TSON-SCHEMA] §7.7) may only make
     * more keys equal still. So {@code 0xFF} and {@code 255} are textually distinct and one key here, which
     * is also what the host {@code Map} would have done with them.
     */
    private static Object keyIdentity(TsonValue key) {
        return switch (key) {
            case TsonAtom atom -> ValueIdentity.of(atom.value());
            case TsonArray array -> array.elements().stream().map(SchemalessTreeReader::keyIdentity).toList();
            case TsonTuple tuple -> tuple.elements().stream().map(SchemalessTreeReader::keyIdentity).toList();
            case TsonRecord record -> {
                Map<String, Object> identity = new LinkedHashMap<>();
                record.fields().forEach((name, value) -> identity.put(name, keyIdentity(value)));
                yield identity;
            }
            case TsonMap map -> map.entries().stream()
                    .map(entry -> List.of(keyIdentity(entry.key()), keyIdentity(entry.value()))).toList();
            // No payload to compare: the kind is the whole identity, and a distinct constant per kind keeps
            // a no-value key apart from the string `null`, which is what that token now resolves to. A `_`
            // key never reaches here at all -- readMap refuses it first, §2.9 -- so this identity is only
            // ever a compound key's own element.
            case TsonAbsent ignored -> KeyKind.ABSENT;
            case TsonMissing ignored -> KeyKind.MISSING;
            // A map key is a data-value, never a scoped-value (§2.3), so no key this reader builds is one.
            // Identity is still the value's -- a scope says where a type name resolves, not what a key is.
            case TsonScopedValue scoped -> keyIdentity(scoped.root());
        };
    }

    /** Identity stand-ins for the payload-free node kinds -- see {@link #keyIdentity}. */
    private enum KeyKind { ABSENT, MISSING }
}
