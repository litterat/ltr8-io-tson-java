package io.ltr8.tson.compiler;

import io.ltr8.annotation.Transparent;
import io.ltr8.annotation.Typename;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClassAnnotated;
import io.ltr8.annotation.Annotation;
import io.ltr8.annotation.Annotations;
import io.ltr8.tson.tree.TsonValue;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassArray;
import io.ltr8.bind.DataClassAtom;
import io.ltr8.bind.DataClassElement;
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassMap;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.DataClassTuple;
import io.ltr8.bind.DataClassUnion;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.atom.BuiltinTypeVocabulary;
import io.ltr8.tson.compiler.config.TsonAtomContext;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

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

    /** The document header this writer emits, if any -- see {@link #describing}. */
    private final TsonDocumentHeader header;

    /**
     * The root value's own type-ref, when {@link #describing} supplied one. Not part of {@link
     * TsonDocumentHeader}: §2.2 is explicit that header directives are properties of the <em>document</em>
     * and the root value's type annotation is not one of them, however adjacent the two look on the wire.
     */
    private final Optional<String> rootTypeName;

    public TsonObjectWriter(DataBindContext context) {
        this(context, TsonDocumentHeader.NONE, Optional.empty());
    }

    public TsonObjectWriter() {
        this(TsonAtomContext.defaultContext());
    }

    private TsonObjectWriter(DataBindContext context, TsonDocumentHeader header, Optional<String> rootTypeName) {
        this.context = context;
        this.vocabularyAtoms = VocabularyAtoms.defaults();
        this.header = header;
        this.rootTypeName = rootTypeName;
    }

    /**
     * A writer whose documents are <b>self-describing</b>: {@code !!schema:"<schemaUri>"} in the header and
     * {@code !rootTypeName} on the root value, which together are everything a reader needs to resolve the
     * schema, pick the type and validate -- nothing out of band. The mirror of {@link
     * TsonObjectReader#withSchema}/{@code readAs}, which is what reads such a document back.
     *
     * <p><b>Both, not just the directive.</b> A bound object carries neither fact: the schemaless writer
     * emits a type-ref only where the value would not read back without one (see {@link #toTson}), so a
     * record writes bare. A {@code !!schema} on its own therefore produces a document whose reader answers
     * "data declares a !!schema but has no root type-ref to select a type" -- half self-describing is not
     * self-describing, so this method takes both and there is no one-argument form to get it half right.
     *
     * <p><b>Derivation, not a setter, and off by default.</b> Emitting a directive by default would change
     * every document this library has ever produced ({@code tson validate --output tson} included), so the
     * plain writer keeps writing a bare value and a caller opts in per writer, exactly as the readers derive.
     *
     * <p>Both strings are the caller's to supply. Deriving them from a compiled schema the bind registry
     * already holds is the shape that would spare a caller naming what it already knows, and it needs the
     * schema-aware writer this library does not have yet ({@code BACKLOG.md}); nothing here forecloses it.
     *
     * @param schemaUri    the governing schema's identity, written as the {@code !!schema} argument
     * @param rootTypeName the schema's own name for the root value's type, written as its type-ref. A value
     *                     that writes a type-ref of its own -- a vocabulary host type, a union member -- is
     *                     refused rather than written twice; see {@link TsonDataEmitter#typeRef}
     */
    public TsonObjectWriter describing(String schemaUri, String rootTypeName) {
        return new TsonObjectWriter(context, header.describing(schemaUri), Optional.of(rootTypeName));
    }

    /**
     * A writer that names {@code documentId} in an {@code !!id} directive -- the document's own identity,
     * emitted first when {@link #describing} is also in force (§2.2 fixes the order).
     */
    public TsonObjectWriter identifiedBy(String documentId) {
        return new TsonObjectWriter(context, header.identifiedBy(documentId), rootTypeName);
    }

    // ── Entry point ──────────────────────────────────────────────────────

    /**
     * Re-emits a structurally-read annotation value; see {@link #writeAnnotations}. Held here rather than
     * built per call, being stateless.
     */
    private final TsonTreeWriter treeWriter = new TsonTreeWriter();

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
     * <p>A record whose bound class declares an {@code Annotations} component gets its wire annotations
     * (§3.1) written back ahead of the value. An annotation's own value round-trips in whatever form the
     * read produced -- a bound object writes like any other value, and one kept structurally (its name
     * resolved to no declared type) writes through the tree writer.
     */
    public String toTson(Object value) {
        StringBuilder text = new StringBuilder();
        write(value, text);
        return text.toString();
    }

    /**
     * Writes {@code value} as TSON <b>into {@code out} as it goes</b>, so a large or open-ended document
     * never exists as a {@code String} -- the write-side counterpart to {@link TsonObjectReader} taking an {@code
     * InputStream}. The bytes are UTF-8 ([TSON-DATA] §9.1), the stream is <b>flushed and not closed</b>
     * (the caller owns it -- an HTTP response body is the case this exists for), and buffering is the
     * encoder's own.
     *
     * <p>{@link #toTson} is this method over a {@link StringBuilder}, kept for the callers that do want the
     * whole document in hand.
     */
    public void write(Object value, OutputStream out) {
        Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        write(value, writer);
        try {
            // Without this the encoder's own buffer is dropped, and a short document writes nothing at all.
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * As {@link #write(Object, OutputStream)}, into any {@link Appendable} -- a caller who already holds a
     * {@code Writer}, or is assembling a larger text around this document. An {@link IOException} from
     * {@code out} surfaces as an {@link UncheckedIOException}; see {@link TsonDataEmitter}.
     */
    public void write(Object value, Appendable out) {
        try {
            TsonDataEmitter writer = new TsonDataEmitter(out);
            header.emit(writer);
            rootTypeName.ifPresent(writer::typeRef);
            if (value == null) {
                writer.nullValue();
                return;
            }
            DataClass dataClass = context.getDescriptor(value.getClass());
            write(value, dataClass, writer);
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
        write(value, dataClass, writer, null);
    }

    /**
     * {@link #write(Object, DataClass, TsonDataEmitter)} with a type-ref to emit at this value's own
     * position, or {@code null} for none.
     *
     * <p><b>The parameter is a seam, and exists so that there is one prologue rather than two.</b> §7.4 orders
     * a data value {@code *annotation [type-ref] core-value}, so a caller with a type-ref of its own -- {@link
     * #writeUnion}, naming the member it dispatched to -- has to place it between the annotations and the
     * core. Without somewhere to hand it in, such a caller must emit the annotations, the type-ref and the
     * core itself, which means reproducing everything above them: the bridge unwrap, the annotated box, the
     * parsed-value case. Reproducing part of it is the failure that shape invites, and it is not hypothetical
     * -- a held template body unwrapped by a caller's own copy of the bridge step reaches {@link #writeCore}
     * as an ordinary record and renders {@code !recordvalue { fields: [ ... ] }}, the description the branch
     * below exists to prevent. With the seam there is nothing to reproduce, and a branch added here is seen by
     * every caller.
     */
    private void write(Object value, DataClass dataClass, TsonDataEmitter writer, String typeRef)
            throws DataBindException {
        try {
            if (value == null) {
                writer.nullValue();
                return;
            }
            if (dataClass instanceof DataClassAnnotated boxed) {
                // The box is framing, not a value shape: its annotations precede the value it wraps, exactly
                // as a record carrier's precede the record (§7.4). Taken apart through its own handles, so
                // nothing here names the carrier class.
                writeAnnotations((Annotations) boxed.annotations().invoke(value), writer);
                write(boxed.value().invoke(value), boxed.valueClass(), writer, typeRef);
                return;
            }
            if (dataClass.bridge().isPresent()) {
                value = dataClass.bridge().get().toData().invoke(value);
            }
            if (value instanceof DataValue ast) {
                // The AST is source, not a value: it records what an author wrote, including which token was
                // quoted and in what order a record's fields stood. Bound like anything else it would write as
                // a faithful description of the wrong thing -- `!recordvalue { fields: [ ... ] }` -- so it is
                // written as the syntax it is (AstWriter). The rule is about the AST rather than about the one
                // body that holds one, so anything carrying a parsed value writes correctly.
                //
                // Asked *after* the bridge, so an `@Transparent` wrapper over a parsed value reaches it: a
                // held template body is exactly that, and unwrapping into the ordinary record path instead
                // would write the description this branch exists to prevent. No other bridge produces one, so
                // nothing else observes the order.
                //
                // A type-ref here is written rather than dropped, so that a caller offering one against a
                // value that already carries its own head meets the emitter's at-most-one refusal instead of
                // silence. Nothing reaches it today: the one transparent member offers none, by definition.
                if (typeRef != null) {
                    writer.typeRef(typeRef);
                }
                AstWriter.write(ast, writer);
                return;
            }
            writeAnnotations(value, dataClass, writer);
            if (typeRef != null) {
                writer.typeRef(typeRef);
            }
            writeCore(value, dataClass, writer);
        } catch (DataBindException | UncheckedIOException | TsonWriteException e) {
            // Both of the non-binding failures pass through rather than being wrapped as "cannot write
            // <class> as TSON", which would blame the object for neither being its fault: an
            // UncheckedIOException is the sink failing, and a TsonWriteException is the emitter refusing
            // what was asked of it (two type annotations on one value, say) and already says so exactly.
            throw e;
        } catch (Throwable t) {
            throw new DataBindException("failed to write value of type " + dataClass.typeClass(), t);
        }
    }

    /**
     * The value itself, with its annotations and any type-ref already emitted and any bridge already applied
     * -- everything §7.4's {@code *annotation [type-ref] core-value} puts before the core value. Reached only
     * from {@link #write}, which owns that prologue; a caller wanting a type-ref of its own hands it to the
     * seam there rather than emitting it and coming here directly.
     */
    private void writeCore(Object value, DataClass dataClass, TsonDataEmitter writer) throws DataBindException {
        try {
            switch (dataClass) {
                case DataClassAtom atom -> writeAtom(value, writer);
                case DataClassRecord record -> writeRecord(value, record, writer);
                case DataClassArray array -> writeArray(value, array, writer);
                case DataClassMap map -> writeMap(value, map, writer);
                case DataClassTuple tuple -> writeTuple(value, tuple, writer);
                case DataClassUnion union -> writeUnion(value, union, writer);
                default -> throw new DataBindException("unsupported DataClass for writing: " + dataClass);
            }
        } catch (DataBindException | UncheckedIOException | TsonWriteException e) {
            // Both of the non-binding failures pass through rather than being wrapped as "cannot write
            // <class> as TSON", which would blame the object for neither being its fault: an
            // UncheckedIOException is the sink failing, and a TsonWriteException is the emitter refusing
            // what was asked of it (two type annotations on one value, say) and already says so exactly.
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


    // ── Annotations (§3.1) ───────────────────────────────────────────────

    /**
     * This value's own wire annotations, ahead of any type-ref and the value itself (§7.4's {@code
     * *annotation [type-ref] core-value}). Only a record can carry them -- a bound scalar, array, map or
     * tuple is a plain Java value with no slot for metadata -- so this is a no-op for everything else, and
     * for the overwhelming majority of records, which declare no carrier.
     */
    private void writeAnnotations(Object value, DataClass dataClass, TsonDataEmitter writer) throws Throwable {
        if (!(dataClass instanceof DataClassRecord record)) {
            return;
        }
        DataClassField carrier = record.annotationsCarrier().orElse(null);
        if (carrier == null || !(carrier.get(value) instanceof Annotations annotations)) {
            return;
        }
        writeAnnotations(annotations, writer);
    }

    private void writeAnnotations(Annotations annotations, TsonDataEmitter writer) throws Throwable {
        for (Annotation annotation : annotations.values()) {
            if (annotation.value().isEmpty()) {
                writer.annotation(annotation.name());
            } else {
                writer.beginAnnotation(annotation.name());
                writeAnnotationValue(annotation.value().get(), writer);
                writer.endAnnotation();
            }
        }
    }

    /**
     * An annotation's value, in whichever Java form the read produced. Ordinarily a bound object, written
     * exactly like any other value; a {@code TsonValue} where the annotation's name resolved to no declared
     * type and the reader kept it structurally, which the tree writer already knows how to emit.
     */
    private void writeAnnotationValue(Object value, TsonDataEmitter writer) throws Throwable {
        if (value instanceof TsonValue node) {
            treeWriter.write(node, writer);
        } else {
            write(value, context.getDescriptor(value.getClass()), writer);
        }
    }

    // ── Records ──────────────────────────────────────────────────────────

    /**
     * The carrier is skipped here rather than written as though it were an ordinary field: its contents are
     * emitted as annotations ahead of the record (see {@link #writeAnnotations}), which is where they came
     * from, not as a field of it.
     * A field that isn't present ({@code Optional.empty()}, or a plain reference field holding
     * {@code null} -- both read the same way on the way in, via {@link DataClassField#isPresent}) is
     * omitted from the record entirely rather than written as {@code null}, matching how the two
     * cases are already treated identically on the read side.
     */
    private void writeRecord(Object value, DataClassRecord dataClass, TsonDataEmitter writer) throws Throwable {
        DataClassField carrier = dataClass.annotationsCarrier().orElse(null);
        writer.beginRecord();
        for (DataClassField field : dataClass.fields()) {
            if (field == carrier || !field.isPresent(value)) {
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
     * The reverse of {@code TsonObjectReader}'s own union-member resolution: given the value's own runtime
     * class (necessarily one specific member, not the union type itself), names that member and writes it
     * like any other value. The name is one canonical choice ({@link #memberTypeRef}) rather than either of
     * the forms the read side accepts -- read/write asymmetry is fine here; a reader benefiting from
     * flexibility doesn't obligate a writer to be equally flexible about its own single output.
     */
    private void writeUnion(Object value, DataClassUnion dataClass, TsonDataEmitter writer) throws Throwable {
        Class<?> memberClass = value.getClass();
        if (!dataClass.isMemberType(memberClass)) {
            throw new DataBindException(
                    "value of type " + memberClass + " is not a member of union " + dataClass.typeClass());
        }
        // Naming the member is the whole of this method's own business; the tag goes to write()'s seam so
        // that the prologue around it -- bridge, annotated box, parsed value -- is the one every other
        // position gets, rather than a copy of part of it maintained here.
        write(value, context.getDescriptor(memberClass), writer, memberTypeRef(memberClass));
    }

    /**
     * The type-ref a union member writes for itself, or {@code null} for none.
     *
     * <p>{@link Typename} if present, else the simple class name lowercased (not the {@code @Typename} value,
     * which is used verbatim) -- matching this codebase's own convention of lowercase type-refs, and the read
     * side's case-insensitive fallback means either case reads back regardless.
     *
     * <p><b>A {@link Transparent} member writes none</b>, being framing rather than shape: it contributes no
     * type-ref of its own and the value it wraps writes whatever head that value has. The cost is stated on
     * the annotation -- with no tag written nothing selects such a member by tag on the way back in, so it
     * round-trips only where a position declares it.
     */
    private static String memberTypeRef(Class<?> memberClass) {
        if (memberClass.isAnnotationPresent(Transparent.class)) {
            return null;
        }
        Typename tn = memberClass.getAnnotation(Typename.class);
        return tn != null ? tn.name() : memberClass.getSimpleName().toLowerCase(Locale.ROOT);
    }
}
