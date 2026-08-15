package io.ltr8.tson.compiler.reader;

import io.ltr8.annotation.Typename;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassArray;
import io.ltr8.bind.DataClassAtom;
import io.ltr8.bind.DataClassElement;
import io.ltr8.bind.DataClassAnnotated;
import io.ltr8.annotation.Annotation;
import io.ltr8.annotation.Annotations;
import io.ltr8.tson.tree.TsonAnnotation;
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassMap;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.DataClassTuple;
import io.ltr8.bind.DataClassUnion;
import io.ltr8.tson.compiler.*;
import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.atom.AtomType;
import io.ltr8.tson.compiler.atom.AtomTypeException;
import io.ltr8.tson.compiler.atom.BuiltinTypeVocabulary;
import io.ltr8.tson.compiler.base.BaseTypeResolver;
import io.ltr8.tson.compiler.base.BaseValue;
import io.ltr8.tson.compiler.config.TsonAtomContext;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * Binds a TSON document to a Java object -- schemaless (Class 1) binding driven by the target Java
 * class's own {@code tson-bind} {@link DataClass} descriptor, which is in effect the schema the data
 * must satisfy. The reflective, class-driven counterpart to the schema-driven {@link TsonTypeReader}
 * (which validates against a resolved TSON schema instead), and the read-side inverse of {@link
 * TsonObjectWriter}. {@code tson-bind}, what this is built on, has no dependency on {@code
 * tson-compiler}/{@code tson-schema} at all, so depending on it directly here is clean -- which is
 * also what lets schema resolution (constructor application, atom refinement, §5.5) use this binding
 * layer directly, in the same module, without a cycle.
 *
 * <p><b>Streams its events, like {@link TsonTypeReader}.</b> The value is read one {@link
 * TsonEvent} at a time off a {@link TsonReadContext} (in practice a {@code TsonDataStream}), never by
 * materializing a full {@code DataValue} tree first -- so a large document never has to be buffered
 * before binding can begin, memory held at any point is proportional to nesting depth. Problems are
 * reported through {@code ctx} using the same model the compiled readers use: the context's own {@link
 * io.ltr8.tson.compiler.TsonDiagnosticsReceiver} decides each problem's fate -- the fail-fast one throws
 * {@link TsonReadException} at the first, a collecting one accumulates every independent problem and reads
 * on. A {@code tson-bind} {@link DataBindException} thrown while narrowing a value or
 * invoking a constructor is caught and re-reported through {@code ctx} too, so a caller sees one
 * uniform error model regardless of which layer noticed the problem.
 *
 * <p><b>A type-ref must link to something</b>, by {@link TypeRefCheck}'s rules, wherever it is written --
 * not just at an atom leaf. A name {@link BuiltinTypeVocabulary} resolves (§5) is a built-in atom, so it
 * must sit on a token; any other name must name the target being bound, and one that names neither is a
 * binding error rather than a marker to ignore. That is not a contradiction of §5.1's "preserve an
 * unrecognized annotation as an uninterpreted marker": that rule is about passive preservation during
 * parsing, not about what an application actively binding to a caller-declared Java type should do with a
 * marker it can't interpret (see {@code SPEC-FEEDBACK.md} #7, whose suggested resolution this is). {@link
 * #preserving} is the opt-in passthrough that resolution also asks for.
 *
 * <p>The two positions differ in how a name gets to "names the target". A <b>container</b> accepts the
 * target's {@link io.ltr8.annotation.Typename} or, failing that, its simple class name case-insensitively --
 * so {@code !point { x: 3  y: 4 }} binds to a Java {@code Point} with nothing annotated, the same match a
 * union's members already get. An <b>atom</b> accepts a declared {@code @Typename} only: its vocabulary is
 * closed, so the loose match would let a UUID-targeted {@code !Uuid} through on the strength of the class
 * being called {@code UUID}, disabling the check §5.1's case-sensitivity exists for. This is why {@code
 * !tags [ "a" ]} bound to a {@code List<String>} is reported -- neither {@code List} nor {@code ArrayList}
 * answers to {@code tags} -- and {@link #preserving} is the way to ask for it anyway.
 *
 * <p>With no type-ref, binding falls through to plain untyped resolution: {@link BaseTypeResolver} (which
 * of null/boolean/number/string) then {@link AtomBinder} (that shape into whatever concrete Java type the
 * target field declares). Both paths share the same final narrowing step ({@code NumberNarrowing}), so a
 * plain {@code 42} and a {@code !uint8 42} bind identically regardless of which path found them.
 *
 * <p><b>No positional form and no schema-composed defaults</b> -- both are schema-layer concepts a
 * schemaless, class-driven bind has no equivalent for; a record must be written braced, and an absent
 * required field is a {@code FIELD_REQUIRED} problem. Duplicate field names resolve last-value-wins
 * (§2.5) by overwriting as they stream, the same as {@link TsonTypeReader}'s own record readers.
 */
public final class SchemalessObjectReader {

    /** {@code EventReducer} records each core-value's own source position for error reporting; annotation capture here has no use for one. */
    private static final BiConsumer<CoreValue, Position> NO_POSITIONS = (value, position) -> {
    };

    private final DataBindContext context;

    /** Whether a type-ref that links to nothing is ignored rather than reported -- see {@link #preserving}. */
    private final boolean preserveUnknownTypeRefs;

    public SchemalessObjectReader(DataBindContext context) {
        this(context, false);
    }

    public SchemalessObjectReader() {
        this(TsonAtomContext.defaultContext());
    }

    private SchemalessObjectReader(DataBindContext context, boolean preserveUnknownTypeRefs) {
        this.context = context;
        this.preserveUnknownTypeRefs = preserveUnknownTypeRefs;
    }

    /**
     * A reader that ignores a type-ref linking to nothing instead of reporting it -- §5.1's uninterpreted
     * marker, for a caller who wants forward-compatible passthrough. Built-in names are still checked.
     */
    public static SchemalessObjectReader preserving(DataBindContext context) {
        return new SchemalessObjectReader(context, true);
    }

    // ── Entry points ─────────────────────────────────────────────────────

    /**
     * Binds one value at {@code ctx}'s current position into {@code targetClass}. The general form, for a
     * caller managing their own {@link TsonReadContext} -- one built with a collecting {@link
     * io.ltr8.tson.compiler.TsonDiagnosticsReceiver} gathers every problem in one pass rather than throwing
     * on the first. Frame-free: whole-document framing belongs to whoever owns the document --
     * {@link io.ltr8.tson.compiler.TsonObjectReader}, which builds the context.
     */
    @SuppressWarnings("unchecked")
    public <T> T read(TsonReadContext ctx, Class<T> targetClass) {
        DataClass dataClass = descriptorFor(ctx, targetClass);
        if (dataClass == null) {
            return null;
        }
        return (T) bind(ctx, dataClass);
    }

    /** Resolves {@code targetClass}'s own descriptor; a class {@code tson-bind} can't analyze (e.g. two {@code Annotations} components) is reported as a {@code SCHEMA_ERROR}, not silently. */
    private DataClass descriptorFor(TsonReadContext ctx, Class<?> targetClass) {
        try {
            return context.getDescriptor(targetClass);
        } catch (DataBindException e) {
            ctx.report(Diagnostic.Code.SCHEMA_ERROR, "cannot bind to " + targetClass + ": " + e.getMessage(),
                    targetClass.getName(), "(unbindable)");
            return null;
        }
    }

    // ── Core dispatch ────────────────────────────────────────────────────

    private Object bind(TsonReadContext ctx, DataClass dataClass) {
        Object result = switch (dataClass) {
            case DataClassAnnotated boxed -> bindAnnotated(ctx, boxed);
            case DataClassAtom atom -> bindAtom(ctx, atom);
            case DataClassRecord record -> bindRecord(ctx, record);
            case DataClassArray array -> bindArray(ctx, array);
            case DataClassMap map -> bindMap(ctx, map);
            case DataClassTuple tuple -> bindTuple(ctx, tuple);
            case DataClassUnion union -> bindUnion(ctx, union);
            default -> {
                ctx.report(Diagnostic.Code.TYPE_MISMATCH, "unsupported target type " + dataClass.typeClass(),
                        "a bindable type", String.valueOf(dataClass));
                yield null;
            }
        };
        if (result != null && dataClass.bridge().isPresent()) {
            try {
                return dataClass.bridge().get().toObject().invoke(result);
            } catch (Throwable t) {
                // A bridge conversion failure is a data problem (e.g. an unrecognized enum member,
                // which surfaces from Enum.valueOf as IllegalArgumentException) -- reported, not
                // rethrown, the same way the tree-based reader wrapped it as a DataBindException.
                ctx.report(Diagnostic.Code.TYPE_MISMATCH, "cannot bind value into " + dataClass.typeClass()
                        + ": " + t.getMessage(), String.valueOf(dataClass.typeClass()), String.valueOf(result));
                return null;
            }
        }
        return result;
    }

    // ── Type-refs (TypeRefCheck's rules) ─────────────────────────────────

    /**
     * Consumes a container-shaped value's {@code annotation* type-ref?} framing and checks the type-ref
     * against {@code target}: a built-in name belongs on a token, not here, and any other name must name the
     * target. Call it where the framing would otherwise just be skipped -- the cursor is left on the
     * core-value either way, so a reported problem never changes what is read next.
     */
    private void containerFraming(TsonReadContext ctx, DataClass target) {
        Optional<String> typeRef = EventSkip.annotationsAndTypeRef(ctx);
        checkContainerTypeRef(ctx, typeRef, target);
    }

    /** {@link #containerFraming}'s check alone, for a caller that consumed the framing itself (a record with an annotations carrier). */
    private void checkContainerTypeRef(TsonReadContext ctx, Optional<String> typeRef, DataClass target) {
        if (typeRef.isEmpty()) {
            return;
        }
        String name = typeRef.get();
        if (BuiltinTypeVocabulary.lookup(name).isPresent()) {
            TypeRefCheck.notScalar(ctx, name, ctx.peek());
        } else if (!preserveUnknownTypeRefs && !TypeRefCheck.names(target.typeClass(), name)) {
            TypeRefCheck.unknown(ctx, name, target.typeClass());
        }
    }

    // ── Atoms: built-in vocabulary (§5) or identification (BaseTypeResolver) + binding (AtomBinder) ──

    private Object bindAtom(TsonReadContext ctx, DataClassAtom dataClass) {
        Optional<String> typeRef = EventSkip.annotationsAndTypeRef(ctx);
        TsonEvent e = ctx.peek();
        if (e instanceof AbsentEvent) {
            ctx.next();
            return bindBaseValue(ctx, new BaseValue.NullValue(), dataClass.dataClass());
        }
        if (!(e instanceof TokenEvent token)) {
            ctx.report(Diagnostic.Code.TYPE_MISMATCH, "expected a token for " + dataClass.typeClass() + ", found " + TypeRefCheck.describe(e),
                    "a token", TypeRefCheck.describe(e));
            EventSkip.coreValue(ctx);
            return null;
        }
        ctx.next();
        TokenValue tokenValue = new TokenValue(token.text(), token.form());

        if (typeRef.isPresent()) {
            Optional<AtomType<?>> atomType = BuiltinTypeVocabulary.lookup(typeRef.get());
            if (atomType.isPresent()) {
                return bindBuiltin(ctx, atomType.get(), typeRef.get(), tokenValue, dataClass.dataClass());
            }
            // Not a built-in: only a name the target class declares outright gets through -- see the class
            // Javadoc on why an atom position takes `declares` rather than `names`.
            if (!preserveUnknownTypeRefs && !TypeRefCheck.declares(dataClass.typeClass(), typeRef.get())) {
                TypeRefCheck.unknown(ctx, typeRef.get(), dataClass.typeClass());
                return null;
            }
        }

        return bindBaseValue(ctx, BaseTypeResolver.resolve(tokenValue), dataClass.dataClass());
    }

    private Object bindBaseValue(TsonReadContext ctx, BaseValue value, Class<?> target) {
        try {
            return AtomBinder.bind(value, target);
        } catch (DataBindException e) {
            ctx.report(Diagnostic.Code.TYPE_MISMATCH, e.getMessage(), "a value bindable to " + target, String.valueOf(value));
            return null;
        }
    }

    /** {@code typeName} is the wire type-ref this atom was resolved from -- the name an author wrote, not the parser's {@code toString()}. */
    private Object bindBuiltin(TsonReadContext ctx, AtomType<?> atomType, String typeName, TokenValue token,
                               Class<?> target) {
        try {
            return atomType.read(token, target);
        } catch (AtomTypeException e) {
            ctx.report(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, e.getMessage(), "a value satisfying !" + typeName,
                    token.text());
            return null;
        } catch (ArithmeticException e) {
            ctx.report(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, token.text() + " does not fit in " + target,
                    "a value that fits " + target, token.text());
            return null;
        } catch (IllegalArgumentException e) {
            ctx.report(Diagnostic.Code.TYPE_MISMATCH, "cannot bind '" + token.text() + "' to " + target,
                    String.valueOf(target), token.text());
            return null;
        }
    }

    // ── Records ──────────────────────────────────────────────────────────

    /**
     * A component whose declared type is {@code io.ltr8.annotation.Annotations} is populated not from a same-named
     * authored field but directly from the record value's *own* wire annotations (§3.1). The record names
     * its own carrier ({@link DataClassRecord#annotationsCarrier()}), settled during analysis, so there is
     * nothing to detect or validate here. Field values' own annotations are never captured -- only the
     * record value's, matching the tree-based reader's own deliberate scope limit.
     */
    private Object bindRecord(TsonReadContext ctx, DataClassRecord dataClass) {
        int diagnosticsBefore = ctx.reported();
        DataClassField[] fields = dataClass.fields();
        DataClassField carrier = dataClass.annotationsCarrier().orElse(null);
        Annotations captured = Annotations.empty();

        if (carrier != null) {
            // UNVALIDATED: no governing schema on this path, so an annotation's value is read structurally
            // and nothing is checked against a declared type -- the same treatment the schemaless tree
            // reader gives it.
            captured = toAnnotations(AnnotationCapture.annotations(ctx, AnnotationTypes.UNVALIDATED));
            checkContainerTypeRef(ctx, EventSkip.typeRef(ctx), dataClass);
        } else {
            containerFraming(ctx, dataClass);
        }

        boolean empty;
        TsonEvent e = ctx.peek();
        if (e instanceof RecordStart) {
            ctx.next();
            empty = false;
        } else if (e instanceof EmptyBraceEvent) {
            ctx.next();
            empty = true;
        } else {
            ctx.report(Diagnostic.Code.TYPE_MISMATCH, "expected a record for " + dataClass.typeClass() + ", found " + TypeRefCheck.describe(e),
                    "a record", TypeRefCheck.describe(e));
            EventSkip.coreValue(ctx);
            return null;
        }

        Map<String, Integer> indexByName = new HashMap<>();
        for (int i = 0; i < fields.length; i++) {
            if (fields[i] != carrier) {
                indexByName.put(fields[i].name(), i);
            }
        }

        Object[] construct = new Object[fields.length];
        boolean[] seen = new boolean[fields.length];

        // The carrier takes no part in field matching: it is filled from what was captured above and marked
        // seen, so the "required field never appeared" pass below skips it.
        for (int i = 0; i < fields.length; i++) {
            if (fields[i] == carrier) {
                construct[carrier.index()] = captured;
                seen[i] = true;
            }
        }

        if (!empty) {
            while (!(ctx.peek() instanceof RecordEnd)) {
                FieldName fieldName = (FieldName) ctx.next();
                Integer idx = indexByName.get(fieldName.name());
                if (idx == null) {
                    EventSkip.scopedValue(ctx); // a data field the target class doesn't declare -- discard
                    continue;
                }
                if (ctx.peek() instanceof SchemaRef) {
                    ctx.next();
                }
                construct[fields[idx].index()] = bindField(ctx, fields[idx]);
                seen[idx] = true; // last occurrence wins (§2.5), reached by overwrite
            }
            ctx.next(); // RecordEnd
        }

        for (int i = 0; i < fields.length; i++) {
            if (seen[i]) {
                continue;
            }
            DataClassField field = fields[i];
            if (field.isRequired()) {
                ctx.field(field.name()).report(Diagnostic.Code.FIELD_REQUIRED,
                        "missing required field '" + field.name() + "' for " + dataClass.typeClass(),
                        "a value for '" + field.name() + "'", "(absent)");
            }
            construct[field.index()] = null;
        }

        return construct(ctx, dataClass.constructor(), construct, diagnosticsBefore, dataClass.typeClass());
    }

    /** One record field's value: the absent sentinel {@code _} binds to {@code null} (a required field left {@code _} is a {@code FIELD_REQUIRED} problem), anything else binds recursively. */
    private Object bindField(TsonReadContext ctx, DataClassField field) {
        if (ctx.peek() instanceof AbsentEvent) {
            ctx.next();
            if (field.isRequired()) {
                ctx.field(field.name()).report(Diagnostic.Code.FIELD_REQUIRED,
                        "required field '" + field.name() + "' is present but absent ('_')",
                        "a value for '" + field.name() + "'", "_");
            }
            return null;
        }
        return bind(ctx.field(field.name()), field.dataClass());
    }

    // ── Arrays ───────────────────────────────────────────────────────────

    private Object bindArray(TsonReadContext ctx, DataClassArray dataClass) {
        containerFraming(ctx, dataClass);
        if (!(ctx.peek() instanceof ArrayStart)) {
            TsonEvent e = ctx.peek();
            ctx.report(Diagnostic.Code.TYPE_MISMATCH, "expected an array for " + dataClass.typeClass() + ", found " + TypeRefCheck.describe(e),
                    "an array", TypeRefCheck.describe(e));
            EventSkip.coreValue(ctx);
            return null;
        }
        ctx.next();
        DataClass elementClass = dataClass.arrayDataClass();
        List<Object> buffered = new ArrayList<>();
        int index = 0;
        while (!(ctx.peek() instanceof ArrayEnd)) {
            if (ctx.peek() instanceof SchemaRef) {
                ctx.next();
            }
            buffered.add(bind(ctx.index(index), elementClass));
            index++;
        }
        ctx.next(); // ArrayEnd

        try {
            Object arrayData = dataClass.constructor().invoke(buffered.size());
            Object iterator = dataClass.iterator().invoke(arrayData);
            for (Object element : buffered) {
                dataClass.put().invoke(arrayData, iterator, element);
            }
            return arrayData;
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Throwable t) {
            ctx.report(Diagnostic.Code.TYPE_MISMATCH, "failed to build " + dataClass.typeClass() + ": " + t.getMessage(),
                    String.valueOf(dataClass.typeClass()), "(" + buffered.size() + " elements)");
            return null;
        }
    }

    // ── Maps ─────────────────────────────────────────────────────────────

    /**
     * A map key is a full {@code data-value} (§2.6), bound recursively exactly like a value is.
     * {@code {}} binds to an empty map (§2.8's deferred choice, resolved to a map here since the
     * target says map), and the absent sentinel {@code _} in key position is rejected (§2.9).
     */
    private Object bindMap(TsonReadContext ctx, DataClassMap dataClass) {
        containerFraming(ctx, dataClass);
        boolean empty;
        TsonEvent e = ctx.peek();
        if (e instanceof MapStart) {
            ctx.next();
            empty = false;
        } else if (e instanceof EmptyBraceEvent) {
            ctx.next();
            empty = true;
        } else {
            ctx.report(Diagnostic.Code.TYPE_MISMATCH, "expected a map for " + dataClass.typeClass() + ", found " + TypeRefCheck.describe(e),
                    "a map", TypeRefCheck.describe(e));
            EventSkip.coreValue(ctx);
            return null;
        }

        try {
            Object mapData = dataClass.constructor().invoke(0);
            if (empty) {
                return mapData; // EmptyBraceEvent already consumed; no MapEnd for {}
            }
            DataClass keyClass = dataClass.keyDataClass();
            DataClass valueClass = dataClass.valueDataClass();
            while (!(ctx.peek() instanceof MapEnd)) {
                if (ctx.peek() instanceof AbsentEvent) {
                    ctx.next(); // the absent key itself
                    ctx.report(Diagnostic.Code.TYPE_MISMATCH, "the absent sentinel '_' must not appear as a map key "
                            + "(§2.9) for " + dataClass.typeClass(), "a real map key", "_");
                    ctx.next(); // MapArrow
                    EventSkip.scopedValue(ctx);
                    continue;
                }
                Object key = bind(ctx, keyClass);
                ctx.next(); // MapArrow
                if (ctx.peek() instanceof SchemaRef) {
                    ctx.next();
                }
                Object value = bind(ctx, valueClass);
                dataClass.put().invoke(mapData, key, value);
            }
            ctx.next(); // MapEnd
            return mapData;
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Throwable t) {
            ctx.report(Diagnostic.Code.TYPE_MISMATCH, "failed to build " + dataClass.typeClass() + ": " + t.getMessage(),
                    String.valueOf(dataClass.typeClass()), "(map)");
            return null;
        }
    }

    // ── Tuples ───────────────────────────────────────────────────────────

    /** A tuple is array-shaped on the wire (§5.3), not record-shaped -- so {@code {}} is never a reading, only {@code []}. Arity is fixed and exact. */
    private Object bindTuple(TsonReadContext ctx, DataClassTuple dataClass) {
        int diagnosticsBefore = ctx.reported();
        containerFraming(ctx, dataClass);
        if (!(ctx.peek() instanceof ArrayStart)) {
            TsonEvent e = ctx.peek();
            ctx.report(Diagnostic.Code.TYPE_MISMATCH, "expected an array for tuple " + dataClass.typeClass() + ", found " + TypeRefCheck.describe(e),
                    "an array", TypeRefCheck.describe(e));
            EventSkip.coreValue(ctx);
            return null;
        }
        ctx.next();

        DataClassElement[] slots = dataClass.elements();
        Object[] construct = new Object[slots.length];
        int index = 0;
        boolean reportedExtra = false;
        while (!(ctx.peek() instanceof ArrayEnd)) {
            if (ctx.peek() instanceof SchemaRef) {
                ctx.next();
            }
            if (index >= slots.length) {
                if (!reportedExtra) {
                    ctx.report(Diagnostic.Code.WRONG_ARITY, "tuple " + dataClass.typeClass() + " has " + slots.length
                            + " elements, found more", slots.length + " elements", "more than " + slots.length);
                    reportedExtra = true;
                }
                EventSkip.dataValue(ctx);
                index++;
                continue;
            }
            construct[index] = bind(ctx.index(index), slots[index].dataClass());
            index++;
        }
        ctx.next(); // ArrayEnd
        if (index < slots.length) {
            ctx.report(Diagnostic.Code.WRONG_ARITY, "tuple " + dataClass.typeClass() + " has " + slots.length
                    + " elements, found " + index, slots.length + " elements", String.valueOf(index));
        }

        return construct(ctx, dataClass.constructor(), construct, diagnosticsBefore, dataClass.typeClass());
    }

    // ── Unions ───────────────────────────────────────────────────────────

    /**
     * Disambiguated by the value's own type annotation (§3.2's {@code !typeName}) -- a member class's
     * {@link Typename} gives the exact match; failing that, its simple class name matches
     * case-insensitively (so {@code !circle} matches a Java class {@code Circle} without every fixture
     * being annotated). The type-ref is consumed here; the value's remaining core-value is then bound
     * as the resolved member.
     */
    private Object bindUnion(TsonReadContext ctx, DataClassUnion dataClass) {
        Optional<String> typeRef = EventSkip.annotationsAndTypeRef(ctx);
        if (typeRef.isEmpty()) {
            TsonEvent e = ctx.peek();
            ctx.report(Diagnostic.Code.UNKNOWN_TYPE_REF, "union type " + dataClass.typeClass()
                    + " requires a type annotation (!typeName) to disambiguate members", "a !typeName", TypeRefCheck.describe(e));
            EventSkip.coreValue(ctx);
            return null;
        }
        Class<?> member = resolveUnionMember(dataClass, typeRef.get());
        if (member == null) {
            ctx.report(Diagnostic.Code.UNKNOWN_TYPE_REF, "no member of union " + dataClass.typeClass()
                    + " matches type name '" + typeRef.get() + "'", "one of " + describeMembers(dataClass), typeRef.get());
            EventSkip.coreValue(ctx);
            return null;
        }
        DataClass memberDataClass = descriptorFor(ctx, member);
        if (memberDataClass == null) {
            EventSkip.coreValue(ctx);
            return null;
        }
        return bind(ctx, memberDataClass);
    }

    /** A declared {@code @Typename} wins over any simple-name match, so the two passes can't be collapsed into one. */
    private static Class<?> resolveUnionMember(DataClassUnion dataClass, String typeName) {
        for (Class<?> member : dataClass.memberTypes()) {
            if (TypeRefCheck.declares(member, typeName)) {
                return member;
            }
        }
        for (Class<?> member : dataClass.memberTypes()) {
            if (TypeRefCheck.names(member, typeName)) {
                return member;
            }
        }
        return null;
    }

    private static String describeMembers(DataClassUnion dataClass) {
        StringBuilder sb = new StringBuilder("[");
        Class<?>[] members = dataClass.memberTypes();
        for (int i = 0; i < members.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(members[i].getSimpleName());
        }
        return sb.append(']').toString();
    }

    // ── Shared helpers ───────────────────────────────────────────────────

    /**
     * Invokes {@code constructor} with the assembled arguments, unless a problem was already reported
     * while reading this value's own fields/elements (collecting mode) -- a real Java constructor
     * can't tolerate a {@code null} argument for a primitive-typed parameter, so constructing after a
     * failure would risk a confusing secondary {@code NullPointerException} on top of the diagnostic
     * already recorded; the caller already has what it needs from {@code ctx.diagnostics()}.
     */
    private Object construct(TsonReadContext ctx, java.lang.invoke.MethodHandle constructor, Object[] arguments,
                             int diagnosticsBefore, Class<?> typeClass) {
        if (ctx.reported() > diagnosticsBefore) {
            return null;
        }
        try {
            return constructor.invoke(arguments);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            ctx.report(Diagnostic.Code.TYPE_MISMATCH, "failed to construct " + typeClass + ": " + t.getMessage(),
                    String.valueOf(typeClass), "(the read field values)");
            return null;
        }
    }


    /**
     * A boxed position ({@code Annotated<T>}): capture what was written at it, then read {@code T}.
     *
     * <p>The order is the whole mechanism and cannot be expressed as an argument list -- the capture must run
     * before the value's own read, so that the delegate's framing consumption finds nothing left. Reading
     * first would consume the annotations as part of the value's framing and discard them.
     */
    private Object bindAnnotated(TsonReadContext ctx, DataClassAnnotated boxed) {
        Annotations annotations = toAnnotations(AnnotationCapture.annotations(ctx, AnnotationTypes.UNVALIDATED));
        Object value = bind(ctx, boxed.valueClass());
        try {
            return boxed.constructor().invoke(value, annotations);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("failed to box an annotated value of " + boxed.typeClass(), t);
        }
    }


    /**
     * The tree reader's capture, as the binding-layer carrier. Both model the same §3.1 attachment; they
     * differ only in the value's Java form, which {@link Annotations} deliberately leaves as {@code Object}
     * because it depends on how the document was read -- here a {@code TsonValue}, since nothing on this path
     * declares what an annotation's value should bind to.
     */
    private static Annotations toAnnotations(List<TsonAnnotation> captured) {
        if (captured.isEmpty()) {
            return Annotations.empty();
        }
        List<Annotation> values = new ArrayList<>(captured.size());
        for (TsonAnnotation annotation : captured) {
            values.add(new Annotation(annotation.name(), annotation.value().map(node -> (Object) node)));
        }
        return Annotations.of(values);
    }
}
