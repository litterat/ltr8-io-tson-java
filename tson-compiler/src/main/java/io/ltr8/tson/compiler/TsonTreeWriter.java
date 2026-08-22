package io.ltr8.tson.compiler;

import io.ltr8.bind.DataBindException;
import io.ltr8.tson.tree.*;
import io.ltr8.tson.tree.TsonValue;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Writes an immutable {@link TsonValue} tree back to TSON text -- the write-side counterpart to {@link
 * TsonTreeReader}, and the tree analogue of {@link TsonObjectWriter} (which writes a Java object graph).
 * Drives {@link TsonDataEmitter} for all grammar-level formatting (delimiters, separators, escaping), the
 * same relationship {@link TsonObjectWriter} has with it.
 *
 * <p><b>Closer to lossless than {@link TsonObjectWriter}</b>, because the tree already carries each node's
 * own type-ref: a {@code TsonAtom(42, "int32")} writes back as {@code !int32 42}, so the integer width
 * survives a read/edit/write round trip (the object writer drops it, having no way to recover it from a bound
 * {@code long}). A JDK-backed vocabulary host type (uuid/uri/date/binary/...) is written with its type-ref
 * and its atom's own {@code write} form, preferring the node's own captured type-ref (e.g. {@code base64url})
 * over the reverse-map default; a plain number/boolean/string is written bare unless the node kept a type-ref.
 *
 * <p><b>Round trip is value-preserving, not byte-identical:</b> a vocabulary value is emitted quoted ({@code
 * !uuid "..."}) whichever way it was written in the source, and a node re-read from the result yields an
 * equal tree. {@link TsonMissing} is a navigation artifact, not a value, so writing one is a programming
 * error ({@link IllegalArgumentException}).
 *
 * <p><b>Wire annotations are re-emitted</b> (§3.1), ahead of each value's type-ref and in the order the tree
 * holds them, repeats included -- so a schemalessly read tree survives a read/write/read round trip with its
 * metadata intact, not just its values. An annotation's own value is written as an ordinary node, so a nested
 * one ({@code @a:@b:val target}) needs no special handling. Only the <i>schemaless</i> reader captures
 * annotations in the first place, so a schema-driven tree still writes back without them -- nothing is lost
 * here, there was nothing on the node to write.
 */
public final class TsonTreeWriter {

    private final Map<Class<?>, VocabularyAtoms.Entry> vocabularyAtoms = VocabularyAtoms.defaults();

    /** The document header this writer emits, if any -- see {@link #describing}. */
    private final DocumentHeader header;

    public TsonTreeWriter() {
        this(DocumentHeader.NONE);
    }

    private TsonTreeWriter(DocumentHeader header) {
        this.header = header;
    }

    /**
     * A writer whose documents are <b>self-describing</b>: {@code !!schema:"<schemaUri>"} in the header,
     * over a root value that already names its own type. The mirror of {@link TsonTreeReader#withSchema},
     * which is what reads such a document back.
     *
     * <p><b>One argument, where {@link TsonObjectWriter#describing} needs two</b>, because a tree already
     * carries what a bound object cannot: a schema-driven read records each node's type, so the root's own
     * {@code !typeName} is written back with it. A root that has no type-ref -- a hand-built node, or one
     * from a schemaless read of an untagged document -- would make a document declaring a schema and then
     * giving a reader no type to select, so writing one is refused rather than half-done.
     *
     * <p><b>Derivation, not a setter, and off by default.</b> Emitting a directive by default would change
     * every document this library has ever produced ({@code tson validate --output tson} included), so the
     * plain writer keeps writing a bare value and a caller opts in per writer, exactly as the readers derive.
     */
    public TsonTreeWriter describing(String schemaUri) {
        return new TsonTreeWriter(header.describing(schemaUri));
    }

    /**
     * A writer that names {@code documentId} in an {@code !!id} directive -- the document's own identity,
     * emitted first when {@link #describing} is also in force (§2.2 fixes the order).
     */
    public TsonTreeWriter identifiedBy(String documentId) {
        return new TsonTreeWriter(header.identifiedBy(documentId));
    }

    /** Writes {@code node} as TSON text -- {@link #write(TsonValue, Appendable)} into a fresh buffer. */
    public String toTson(TsonValue node) {
        StringBuilder text = new StringBuilder();
        write(node, text);
        return text.toString();
    }

    /**
     * Writes {@code node} as TSON <b>into {@code out} as it goes</b>, so a large document never exists as a
     * {@code String} -- the write-side counterpart to {@link TsonTreeReader} taking an {@code InputStream}.
     * The bytes are UTF-8 ([TSON-DATA] §9.1), the stream is <b>flushed and not closed</b> (the caller owns
     * it), and buffering is the encoder's own.
     *
     * <p>The tree itself is of course already in memory -- that is what a tree is. What this saves is the
     * <em>second</em> copy: the rendered document, which for a large tree is the bigger of the two.
     */
    public void write(TsonValue node, OutputStream out) {
        Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        write(node, writer);
        try {
            // Without this the encoder's own buffer is dropped, and a short document writes nothing at all.
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * As {@link #write(TsonValue, OutputStream)}, into any {@link Appendable}. An {@link IOException} from
     * {@code out} surfaces as an {@link UncheckedIOException}; see {@link TsonDataEmitter}.
     */
    public void write(TsonValue node, Appendable out) {
        try {
            if (header.schema().isPresent() && node.typeRef().isEmpty()) {
                throw new TsonWriteException("a document declaring !!schema \"" + header.schema().get()
                        + "\" needs a root type-ref to select a type, and this root node carries none -- read"
                        + " the tree against its schema (which records each node's type) or set one on the"
                        + " root before writing", null);
            }
            TsonDataEmitter emitter = new TsonDataEmitter(out);
            header.emit(emitter);
            writeNode(node, emitter);
        } catch (DataBindException e) {
            throw new TsonWriteException("cannot write TsonValue as TSON: " + e.getMessage(), e);
        }
    }

    /**
     * Writes {@code node} into an emitter a caller already owns, rather than into a fresh document. Exists
     * for {@link TsonObjectWriter}, which needs it for one case only: an annotation whose name the governing
     * schema does not declare is read structurally, so its value arrives as a node in an otherwise wholly
     * object-bound value, and re-emitting it is exactly this method's job.
     */
    void write(TsonValue node, TsonDataEmitter out) throws DataBindException {
        writeNode(node, out);
    }

    private void writeNode(TsonValue node, TsonDataEmitter out) throws DataBindException {
        writeAnnotations(node.annotations(), out);
        switch (node) {
            case TsonRecord record -> writeRecord(record, out);
            case TsonMap map -> writeMap(map, out);
            case TsonArray array -> writeSequence(array.elements(), array.typeRef(), out);
            case TsonTuple tuple -> writeSequence(tuple.elements(), tuple.typeRef(), out);
            case TsonAtom atom -> writeAtom(atom, out);
            case TsonAbsent absentNode -> {
                absentNode.typeRef().ifPresent(out::typeRef);
                out.absentValue();
            }
            case TsonMissing missing -> throw new IllegalArgumentException(
                    "a TsonMissing is a navigation artifact and cannot be written as TSON; navigation failed at \""
                            + missing.path() + "\"");
        }
    }

    /**
     * A value's own annotations, ahead of its type-ref and core-value -- the order {@code data-value =
     * *annotation [type-ref] core-value} fixes (§7.4), which is why this runs at the top of {@link
     * #writeNode} rather than inside each shape's own method. Order and repeats are preserved as the tree
     * holds them, matching §3.1's rule that every occurrence survives in source order.
     *
     * <p>An annotation's value is written through {@link #writeNode} like any other node, so one that
     * carries annotations of its own ({@code @a:@b:val target}) nests without a special case.
     */
    private void writeAnnotations(List<TsonAnnotation> annotations, TsonDataEmitter out) throws DataBindException {
        for (TsonAnnotation annotation : annotations) {
            if (annotation.value().isPresent()) {
                out.beginAnnotation(annotation.name());
                writeNode(annotation.value().get(), out);
                out.endAnnotation();
            } else {
                out.annotation(annotation.name());
            }
        }
    }

    private void writeRecord(TsonRecord record, TsonDataEmitter out) throws DataBindException {
        record.typeRef().ifPresent(out::typeRef);
        out.beginRecord();
        for (Map.Entry<String, TsonValue> field : record.fields().entrySet()) {
            out.field(field.getKey());
            writeNode(field.getValue(), out);
        }
        out.endRecord();
    }

    private void writeMap(TsonMap map, TsonDataEmitter out) throws DataBindException {
        map.typeRef().ifPresent(out::typeRef);
        out.beginMap();
        for (TsonMap.Entry entry : map.entries()) {
            out.beforeMapEntry();
            writeNode(entry.key(), out);
            out.mapArrow();
            writeNode(entry.value(), out);
        }
        out.endMap();
    }

    /** Arrays and tuples share the {@code [ ... ]} shape (§2.7); a tuple keeps its type-ref, if any. */
    private void writeSequence(List<TsonValue> elements, Optional<String> typeRef, TsonDataEmitter out)
            throws DataBindException {
        typeRef.ifPresent(out::typeRef);
        out.beginArray();
        for (TsonValue element : elements) {
            out.beforeArrayElement();
            writeNode(element, out);
        }
        out.endArray();
    }

    /**
     * A JDK-backed vocabulary host type (its Java class is in the reverse map) is written with a type-ref and
     * its atom's own quoted {@code write} form -- preferring the node's own captured type-ref over the map
     * default, so a more specific one (e.g. {@code base64url}) survives. Everything else is a base type
     * (number/boolean/string) written via {@link AtomWriter#writeDefaultAtom}, keeping the node's own
     * type-ref if it has one so an {@code !int32}/{@code !uint8} width isn't lost.
     */
    private void writeAtom(TsonAtom node, TsonDataEmitter out) throws DataBindException {
        Object value = node.value();
        VocabularyAtoms.Entry vocab = vocabularyAtoms.get(value.getClass());
        if (vocab != null) {
            out.typeRef(node.typeRef().orElse(vocab.typeRef())).quotedString(vocab.write(value));
        } else {
            node.typeRef().ifPresent(out::typeRef);
            AtomWriter.writeDefaultAtom(value, out);
        }
    }
}
