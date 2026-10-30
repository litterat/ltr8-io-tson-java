package io.ltr8.tson.compiler.writer;

import io.ltr8.bind.DataBindException;
import io.ltr8.tson.atom.VocabularyAtoms;
import io.ltr8.tson.compiler.TsonDataEmitter;
import io.ltr8.tson.tree.TsonVoid;
import io.ltr8.tson.tree.TsonAnnotation;
import io.ltr8.tson.tree.TsonArray;
import io.ltr8.tson.tree.TsonAtom;
import io.ltr8.tson.tree.TsonMap;
import io.ltr8.tson.tree.TsonMissing;
import io.ltr8.tson.tree.TsonRecord;
import io.ltr8.tson.tree.TsonReference;
import io.ltr8.tson.tree.TsonTuple;
import io.ltr8.tson.tree.TsonValue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Writes one {@link TsonValue} into an emitter a caller already owns, and stops -- the engine {@code
 * TsonTreeWriter} is the facade over. The split is the one {@code TsonObjectReader} makes over {@code
 * DataClassObjectReader}: a front door owns the <em>document</em> (its header, the sinks it writes to, the
 * configuration it writes under) where an engine owns one value and no framing at all.
 *
 * <p>Two callers need exactly that. {@code TsonTreeWriter} adds a document around it, and {@link
 * DataClassObjectWriter} re-emits an annotation whose name the governing schema does not declare: such a
 * value is read structurally, so it arrives as a node inside an otherwise wholly object-bound value.
 *
 * <p>Stateless past its atom table, so one instance serves every write.
 */
public final class TreeValueWriter {

    private final Map<Class<?>, VocabularyAtoms.Entry> vocabularyAtoms = VocabularyAtoms.defaults();

    /** Writes {@code node} into {@code out}, contributing no header and no framing of its own. */
    public void write(TsonValue node, TsonDataEmitter out) throws DataBindException {
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
            case TsonReference reference -> {
                reference.typeRef().ifPresent(out::typeRef);
                out.reference(reference(reference));
            }
            case TsonVoid voidNode -> {
                voidNode.typeRef().ifPresent(out::typeRef);
                out.voidValue();
            }
            case TsonMissing missing -> throw new IllegalArgumentException(
                    "a TsonMissing is a navigation artifact and cannot be written as TSON; navigation failed at \""
                            + missing.path() + "\"");
        }
    }

    /**
     * A schemaless reference as the reference it was read from: a string argument that is a qualified name a
     * reference, any other a quoted token, and every other value its text, unquoted.
     */
    private static io.ltr8.tson.schema.meta.TypeRef reference(TsonReference reference) {
        List<io.ltr8.tson.schema.meta.TypeArgument> arguments = new java.util.ArrayList<>();
        for (TsonValue argument : reference.arguments()) {
            arguments.add(switch (argument) {
                case TsonReference nested -> new io.ltr8.tson.schema.meta.TypeArgument.Ref(reference(nested));
                case TsonAtom atom when atom.value() instanceof String text
                        && io.ltr8.tson.atom.ReferenceGrammar.isQualifiedName(text) ->
                        new io.ltr8.tson.schema.meta.TypeArgument.Ref(io.ltr8.tson.schema.meta.TypeRef.of(text));
                case TsonAtom atom when atom.value() instanceof String text ->
                        new io.ltr8.tson.schema.meta.TypeArgument.Value(new io.ltr8.tson.schema.meta.Token(text,
                                io.ltr8.tson.schema.meta.Token.Form.SINGLE_LINE_QUOTED));
                default -> new io.ltr8.tson.schema.meta.TypeArgument.Value(new io.ltr8.tson.schema.meta.Token(
                        String.valueOf(argument), io.ltr8.tson.schema.meta.Token.Form.UNQUOTED));
            });
        }
        return new io.ltr8.tson.schema.meta.TypeRef(reference.name(), arguments);
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
        if (value instanceof io.ltr8.tson.schema.meta.TypeRef reference) {
            // A reference a schema-directed read delivered: written as the reference it is, unquoted.
            node.typeRef().ifPresent(out::typeRef);
            out.reference(reference);
            return;
        }
        VocabularyAtoms.Entry vocab = vocabularyAtoms.get(value.getClass());
        if (vocab != null) {
            out.typeRef(node.typeRef().orElseGet(() -> vocab.typeRef(value))).quotedString(vocab.write(value));
        } else {
            node.typeRef().ifPresent(out::typeRef);
            AtomWriter.writeDefaultAtom(value, out);
        }
    }
}
