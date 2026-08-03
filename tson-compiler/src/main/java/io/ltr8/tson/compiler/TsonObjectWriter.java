package io.ltr8.tson.compiler;

import io.ltr8.annotation.Typename;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassArray;
import io.ltr8.bind.DataClassAtom;
import io.ltr8.bind.DataClassElement;
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassMap;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.DataClassTuple;
import io.ltr8.bind.DataClassUnion;
import io.ltr8.tson.compiler.atom.BuiltinTypeVocabulary;
import io.ltr8.tson.compiler.config.TsonAtomContext;

import java.util.Locale;
import java.util.Map;

/**
 * The write-side counterpart to {@link TsonObjectReader} -- given a Java object and its {@link
 * DataClass} descriptor from {@code tson-bind}, writes it as TSON text. See {@link
 * TsonObjectReader}'s own Javadoc for the read/write split and why this pair lives in {@code
 * tson-compiler}.
 */
public final class TsonObjectWriter {

    private final DataBindContext context;

    /**
     * The reverse of {@link BuiltinTypeVocabulary}'s name-&gt;{@code AtomType} map, keyed by bound Java
     * class -- see {@link VocabularyAtoms}. A per-writer mutable copy so a future caller can extend it with
     * their own {@code AtomType}, the write-side mirror of {@code DataBindContext#registerAtom}.
     */
    private final Map<Class<?>, VocabularyAtoms.Entry> vocabularyAtoms;

    public TsonObjectWriter(DataBindContext context) {
        this.context = context;
        this.vocabularyAtoms = VocabularyAtoms.defaults();
    }

    public TsonObjectWriter() {
        this(TsonAtomContext.defaultContext());
    }

    // ── Entry point ──────────────────────────────────────────────────────

    /**
     * Writes {@code value} as TSON text -- mainly useful as a debugging tool (inspect what a bound
     * object actually contains) rather than a guaranteed-lossless serializer. Emits a {@code
     * !typeName} type-ref only where one is actually needed for the value to read back correctly:
     * exactly the built-in vocabulary's JDK-backed host types (uuid/uri/ipv4/ipv6/date/time/
     * datetime/binary/rational/complex/duration), none of which round-trip through default value
     * resolution (§4) on their own. Everything default resolution *does* already recover -- plain
     * numbers, booleans, strings, {@code null} -- is written bare, which means the integer family's
     * exact width is **not** preserved: a field bound from {@code !uint8 42} writes back as plain
     * {@code 42}, indistinguishable from a field that was never {@code !uint8}-typed at all. That's
     * not a bug to fix -- a schemaless writer has no way to know the width was ever there in the
     * first place, the same reason a schemaless reader has no way to reject an out-of-range value
     * without the annotation.
     *
     * <p>{@code @Annotated}-captured wire-format annotations are not re-emitted yet -- deferred to
     * a follow-up, not part of this first pass (values only).
     */
    public String toTson(Object value) {
        try {
            TsonDataEmitter writer = new TsonDataEmitter();
            if (value == null) {
                writer.nullValue();
                return writer.toString();
            }
            DataClass dataClass = context.getDescriptor(value.getClass());
            write(value, dataClass, writer);
            return writer.toString();
        } catch (DataBindException e) {
            throw new TsonWriteException("cannot write " + value.getClass() + " as TSON: " + e.getMessage(), e);
        }
    }

    // ── Core dispatch ────────────────────────────────────────────────────

    /**
     * {@code null} (the base type, distinct from omitting the field entirely -- see {@link
     * #writeRecord}) aside: a bridge, if present, is unwrapped once, up front -- covers plain Java
     * {@code enum}s and {@code Rational}/{@code Complex}/{@code IsoDuration} reached through a
     * caller's own {@code DataBridge} (all via {@code DataBindContext#registerAtom(Class,
     * DataBridge)}, which always attaches to a {@code DataClassAtom}), and, in principle, a {@code
     * DataClassRecord}'s own (unrelated) {@code ToData}-interface bridge too, the same way {@code
     * litterat-json}'s {@code JsonMapper.toJson} handles both uniformly -- {@code dataClass} itself
     * is reused unchanged afterward, not re-resolved, since a bridged {@code DataClassRecord}'s
     * fields are already resolved against the bridge's own data type (see {@code
     * DefaultRecordBinder#resolveRecord}), not the original wrapper type. Then dispatches on
     * {@code dataClass}'s own kind.
     */
    private void write(Object value, DataClass dataClass, TsonDataEmitter writer) throws DataBindException {
        try {
            if (value == null) {
                writer.nullValue();
                return;
            }
            if (dataClass.bridge().isPresent()) {
                value = dataClass.bridge().get().toData().invoke(value);
            }
            switch (dataClass) {
                case DataClassAtom atom -> writeAtom(value, writer);
                case DataClassRecord record -> writeRecord(value, record, writer);
                case DataClassArray array -> writeArray(value, array, writer);
                case DataClassMap map -> writeMap(value, map, writer);
                case DataClassTuple tuple -> writeTuple(value, tuple, writer);
                case DataClassUnion union -> writeUnion(value, union, writer);
                default -> throw new DataBindException("unsupported DataClass for writing: " + dataClass);
            }
        } catch (DataBindException e) {
            throw e;
        } catch (Throwable t) {
            throw new DataBindException("failed to write value of type " + dataClass.typeClass(), t);
        }
    }

    /**
     * {@code value} is already unwrapped from any bridge by the time it gets here (see {@link
     * #write}) -- a lookup in {@link #vocabularyAtoms} decides the rest: found means one of the
     * built-in vocabulary's known host types, written with its own type-ref (see {@link #toTson});
     * not found falls back to {@link AtomWriter#writeDefaultAtom}. An enum's bridge produces a
     * plain {@code String}, which lands in that fallback and so writes as a quoted string rather
     * than unquoted -- both read back identically, not worth a special case purely for that
     * formatting difference.
     */
    private void writeAtom(Object value, TsonDataEmitter writer) throws DataBindException {
        VocabularyAtoms.Entry vocab = vocabularyAtoms.get(value.getClass());
        if (vocab != null) {
            writer.typeRef(vocab.typeRef()).quotedString(vocab.write(value));
        } else {
            AtomWriter.writeDefaultAtom(value, writer);
        }
    }

    // ── Records ──────────────────────────────────────────────────────────

    /**
     * {@code @Annotated}-captured annotations aren't re-emitted yet (see {@link #toTson}) -- that
     * field is skipped entirely here, not written as though it were an ordinary structural value.
     * A field that isn't present ({@code Optional.empty()}, or a plain reference field holding
     * {@code null} -- both read the same way on the way in, via {@link DataClassField#isPresent}) is
     * omitted from the record entirely rather than written as {@code null}, matching how the two
     * cases are already treated identically on the read side.
     */
    private void writeRecord(Object value, DataClassRecord dataClass, TsonDataEmitter writer) throws Throwable {
        writer.beginRecord();
        for (DataClassField field : dataClass.fields()) {
            if (field.isAnnotationsCarrier() || !field.isPresent(value)) {
                continue;
            }
            writer.field(field.name());
            write(field.get(value), field.dataClass(), writer);
        }
        writer.endRecord();
    }

    // ── Arrays ───────────────────────────────────────────────────────────

    private void writeArray(Object value, DataClassArray dataClass, TsonDataEmitter writer) throws Throwable {
        writer.beginArray();
        int size = (int) dataClass.size().invoke(value);
        Object iterator = dataClass.iterator().invoke(value);
        DataClass elementClass = dataClass.arrayDataClass();
        for (int i = 0; i < size; i++) {
            Object element = dataClass.get().invoke(value, iterator);
            writer.beforeArrayElement();
            write(element, elementClass, writer);
        }
        writer.endArray();
    }

    // ── Maps ─────────────────────────────────────────────────────────────

    private void writeMap(Object value, DataClassMap dataClass, TsonDataEmitter writer) throws Throwable {
        writer.beginMap();
        Object iterator = dataClass.iterator().invoke(value);
        DataClass keyClass = dataClass.keyDataClass();
        DataClass valueClass = dataClass.valueDataClass();
        Object entry;
        while ((entry = dataClass.next().invoke(iterator)) != null) {
            Object key = dataClass.key().invoke(entry);
            Object entryValue = dataClass.value().invoke(entry);
            writer.beforeMapEntry();
            write(key, keyClass, writer);
            writer.mapArrow();
            write(entryValue, valueClass, writer);
        }
        writer.endMap();
    }

    // ── Tuples ───────────────────────────────────────────────────────────

    /** No type-ref -- a tuple is array-shaped on the wire, and (schemaless) its tuple-ness at all
     * isn't recoverable without a schema any more than an integer's exact width is (see {@link
     * #toTson}); this writes a plain array, indistinguishable on the wire from an ordinary one. */
    private void writeTuple(Object value, DataClassTuple dataClass, TsonDataEmitter writer) throws Throwable {
        writer.beginArray();
        for (DataClassElement element : dataClass.elements()) {
            writer.beforeArrayElement();
            write(element.accessor().invoke(value), element.dataClass(), writer);
        }
        writer.endArray();
    }

    // ── Unions ───────────────────────────────────────────────────────────

    /**
     * The reverse of {@code TsonObjectReader}'s own union-member resolution: given the value's own
     * runtime class (necessarily one specific member, not the union type itself), picks one
     * canonical type-ref name for it -- {@link Typename} if present, else the simple class name --
     * rather than accepting either form the way the read side does. Read/write asymmetry is fine
     * here; a reader benefiting from flexibility doesn't obligate a writer to be equally flexible
     * about its own single output.
     */
    private void writeUnion(Object value, DataClassUnion dataClass, TsonDataEmitter writer) throws Throwable {
        Class<?> memberClass = value.getClass();
        if (!dataClass.isMemberType(memberClass)) {
            throw new DataBindException(
                    "value of type " + memberClass + " is not a member of union " + dataClass.typeClass());
        }
        // Lowercased when falling back to the simple class name (not the @Typename value, used
        // verbatim) -- matches this codebase's own convention of lowercase type-refs, and the read
        // side's case-insensitive fallback match means either case reads back correctly regardless.
        Typename tn = memberClass.getAnnotation(Typename.class);
        writer.typeRef(tn != null ? tn.name() : memberClass.getSimpleName().toLowerCase(Locale.ROOT));
        write(value, context.getDescriptor(memberClass), writer);
    }
}
