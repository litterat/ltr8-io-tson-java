package io.ltr8.tson.compiler;

import io.ltr8.bind.DataBindException;
import io.ltr8.tson.tree.AbsentNode;
import io.ltr8.tson.tree.ArrayNode;
import io.ltr8.tson.tree.AtomNode;
import io.ltr8.tson.tree.MapNode;
import io.ltr8.tson.tree.MissingNode;
import io.ltr8.tson.tree.NullNode;
import io.ltr8.tson.tree.RecordNode;
import io.ltr8.tson.tree.TupleNode;
import io.ltr8.tson.tree.TsonAnnotation;
import io.ltr8.tson.tree.TsonNode;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Writes an immutable {@link TsonNode} tree back to TSON text -- the write-side counterpart to {@link
 * TsonTreeReader}, and the tree analogue of {@link TsonObjectWriter} (which writes a Java object graph).
 * Drives {@link TsonDataEmitter} for all grammar-level formatting (delimiters, separators, escaping), the
 * same relationship {@link TsonObjectWriter} has with it.
 *
 * <p><b>Closer to lossless than {@link TsonObjectWriter}</b>, because the tree already carries each node's
 * own type-ref: an {@code AtomNode(42, "int32")} writes back as {@code !int32 42}, so the integer width
 * survives a read/edit/write round trip (the object writer drops it, having no way to recover it from a bound
 * {@code long}). A JDK-backed vocabulary host type (uuid/uri/date/binary/...) is written with its type-ref
 * and its atom's own {@code write} form, preferring the node's own captured type-ref (e.g. {@code base64url})
 * over the reverse-map default; a plain number/boolean/string is written bare unless the node kept a type-ref.
 *
 * <p><b>Round trip is value-preserving, not byte-identical:</b> a vocabulary value is emitted quoted ({@code
 * !uuid "..."}) whichever way it was written in the source, and a node re-read from the result yields an
 * equal tree. {@link MissingNode} is a navigation artifact, not a value, so writing one is a programming
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

    public TsonTreeWriter() {
    }

    /** Writes {@code node} as TSON text. */
    public String toTson(TsonNode node) {
        try {
            TsonDataEmitter out = new TsonDataEmitter();
            writeNode(node, out);
            return out.toString();
        } catch (DataBindException e) {
            throw new TsonWriteException("cannot write TsonNode as TSON: " + e.getMessage(), e);
        }
    }

    /**
     * Writes {@code node} into an emitter a caller already owns, rather than into a fresh document. Exists
     * for {@link TsonObjectWriter}, which needs it for one case only: an annotation whose name the governing
     * schema does not declare is read structurally, so its value arrives as a node in an otherwise wholly
     * object-bound value, and re-emitting it is exactly this method's job.
     */
    void write(TsonNode node, TsonDataEmitter out) throws DataBindException {
        writeNode(node, out);
    }

    private void writeNode(TsonNode node, TsonDataEmitter out) throws DataBindException {
        writeAnnotations(node.annotations(), out);
        switch (node) {
            case RecordNode record -> writeRecord(record, out);
            case MapNode map -> writeMap(map, out);
            case ArrayNode array -> writeSequence(array.elements(), array.typeRef(), out);
            case TupleNode tuple -> writeSequence(tuple.elements(), tuple.typeRef(), out);
            case AtomNode atom -> writeAtom(atom, out);
            case NullNode nullNode -> {
                nullNode.typeRef().ifPresent(out::typeRef);
                out.nullValue();
            }
            case AbsentNode absentNode -> {
                absentNode.typeRef().ifPresent(out::typeRef);
                out.absentValue();
            }
            case MissingNode ignored -> throw new IllegalArgumentException(
                    "a MissingNode is a navigation artifact and cannot be written as TSON");
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

    private void writeRecord(RecordNode record, TsonDataEmitter out) throws DataBindException {
        record.typeRef().ifPresent(out::typeRef);
        out.beginRecord();
        for (Map.Entry<String, TsonNode> field : record.fields().entrySet()) {
            out.field(field.getKey());
            writeNode(field.getValue(), out);
        }
        out.endRecord();
    }

    private void writeMap(MapNode map, TsonDataEmitter out) throws DataBindException {
        map.typeRef().ifPresent(out::typeRef);
        out.beginMap();
        for (MapNode.Entry entry : map.entries()) {
            out.beforeMapEntry();
            writeNode(entry.key(), out);
            out.mapArrow();
            writeNode(entry.value(), out);
        }
        out.endMap();
    }

    /** Arrays and tuples share the {@code [ ... ]} shape (§2.7); a tuple keeps its type-ref, if any. */
    private void writeSequence(List<TsonNode> elements, Optional<String> typeRef, TsonDataEmitter out)
            throws DataBindException {
        typeRef.ifPresent(out::typeRef);
        out.beginArray();
        for (TsonNode element : elements) {
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
    private void writeAtom(AtomNode node, TsonDataEmitter out) throws DataBindException {
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
