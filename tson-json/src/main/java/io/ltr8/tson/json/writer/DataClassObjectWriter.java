package io.ltr8.tson.json.writer;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassAnnotated;
import io.ltr8.bind.DataClassArray;
import io.ltr8.bind.DataClassAtom;
import io.ltr8.bind.DataClassElement;
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassMap;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.DataClassTuple;
import io.ltr8.bind.DataClassUnion;
import io.ltr8.tson.atom.VocabularyAtoms;
import io.ltr8.tson.base.WriteException;
import io.ltr8.tson.json.JsonDataEmitter;

import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.Map;

/**
 * Writes one Java object at one {@link DataClass} descriptor into an emitter a caller already owns, and
 * stops -- the engine {@code JsonObjectWriter} is the facade over, and the exact inverse of this module's
 * {@code DataClassObjectReader}. The split is that reader's: a front door owns the <em>document</em> (the
 * sinks it writes to, the form it writes in) where an engine owns one value and contributes no framing.
 *
 * <p><b>The class stands in for the schema, in both directions.</b> [TSON-JSON] §4.1 makes reading
 * schema-directed and this module's reader lets the target class play that part; writing is the same
 * arrangement seen from the other end -- the descriptor decides whether a value is an object, an array or a
 * leaf, and nothing here inspects a value to guess. That is what makes a {@code Map} an object and a tuple
 * an array without either being ambiguous at the point it is written.
 *
 * <p><b>What it refuses, it refuses because the reader could not take it back.</b> A choice with no
 * discriminator has no schemaless dispatch (§8.2 admits exactly two routes to an untagged one, both facts a
 * schema states), so writing a union member would produce a document this library cannot read; a non-finite
 * {@code double} at a position with no string spelling has no JSON number form (§3.1). Both are {@link
 * WriteException}. Refusing beats emitting something that only looks like a document.
 *
 * <p>Stateless past its atom table, so one instance serves every write.
 */
public final class DataClassObjectWriter {

    private final DataBindContext context;

    /**
     * The reverse of the built-in vocabulary's name-&gt;{@code AtomType} map, keyed by the <em>wire</em>
     * class -- see {@link VocabularyAtoms}. A per-writer mutable copy, the write-side mirror of {@code
     * DataBindContext#registerAtom}.
     *
     * <p><b>Every entry in it encodes as a JSON string</b>, and that is not a coincidence to be rechecked
     * per family: the table holds the host types the vocabulary reads to that are not numbers or booleans --
     * a {@code UUID}, a {@code LocalDate}, a {@code byte[]} -- and §5.1 hands a JSON string's content to the
     * atom's own parser exactly as a TSON quoted token's text would be. So one lookup answers both "is this
     * a vocabulary atom" and "how is it spelled", where dispatching on {@code instanceof Number} would put a
     * {@code long} and a {@code BigInteger} down one branch and write neither by its family.
     */
    private final Map<Class<?>, VocabularyAtoms.Entry> vocabularyAtoms = VocabularyAtoms.defaults();

    public DataClassObjectWriter(DataBindContext context) {
        this.context = context;
    }

    /**
     * Writes {@code value} at its own class's descriptor, contributing no framing of its own.
     *
     * <p>A class that cannot be taken apart arrives as {@link WriteException} rather than the checked
     * {@code DataBindException}, so one unchecked type covers every way a write can fail and a caller has
     * one thing to catch -- the same arrangement {@code TsonObjectWriter} makes.
     */
    public void write(Object value, JsonDataEmitter out) {
        try {
            if (value == null) {
                out.nullValue();
                return;
            }
            write(value, context.getDescriptor(value.getClass()), out);
        } catch (DataBindException e) {
            throw new WriteException("cannot write " + value.getClass() + " as JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Writes {@code value} at {@code dataClass}.
     *
     * <p>The prologue -- the null case, the annotated box, the bridge -- is here rather than repeated per
     * shape, so a branch added to it is seen by every position.
     */
    private void write(Object value, DataClass dataClass, JsonDataEmitter out) throws DataBindException {
        try {
            if (value == null) {
                // §7 makes JSON null the absent sentinel's spelling at a typed position, which is what a
                // reader of this document will apply. Here it is simply the one spelling absence has.
                out.nullValue();
                return;
            }
            if (dataClass instanceof DataClassAnnotated boxed) {
                // The box is framing, not a shape. Its annotations are dropped rather than written: §3.3's
                // annotation object is the schema-directed decode's, and this module's reader binds every
                // carrier to Annotations.empty() -- so writing them would produce members no reader here
                // takes back, which is worse than the symmetric loss.
                write(boxed.value().invoke(value), boxed.valueClass(), out);
                return;
            }
            if (dataClass.bridge().isPresent()) {
                value = dataClass.bridge().get().toData().invoke(value);
            }
            writeCore(value, dataClass, out);
        } catch (DataBindException | WriteException | UncheckedIOException e) {
            // Passed through rather than wrapped as "cannot write <class> as JSON", which would blame the
            // object for what is not its fault: an UncheckedIOException is the sink failing and a
            // WriteException already says exactly what the encoding would not take.
            throw e;
        } catch (Throwable t) {
            throw new DataBindException("failed to write value of type " + dataClass.typeClass(), t);
        }
    }

    private void writeCore(Object value, DataClass dataClass, JsonDataEmitter out) throws Throwable {
        switch (dataClass) {
            case DataClassAtom ignored -> writeAtom(value, out);
            case DataClassRecord record -> writeRecord(value, record, out);
            case DataClassArray array -> writeArray(value, array, out);
            case DataClassMap map -> writeMap(value, map, out);
            case DataClassTuple tuple -> writeTuple(value, tuple, out);
            case DataClassUnion union -> throw new WriteException(
                    ("%s is a choice, and JSON has no schemaless way to say which member this is -- "
                            + "[TSON-JSON] §8.2 admits an untagged choice by a declared discriminator or by "
                            + "class-stable disjoint variants, both of which a schema states. Writing one "
                            + "here would produce a document this library could not read back.")
                            .formatted(union.typeClass().getSimpleName()));
            default -> throw new WriteException(
                    "no rule for writing " + dataClass.getClass().getSimpleName() + " as JSON");
        }
    }

    // ── Leaves ───────────────────────────────────────────────────────────

    /**
     * One host value as one JSON leaf.
     *
     * <p><b>The vocabulary is asked first, and answers with a string</b> (see {@link #vocabularyAtoms}).
     * What falls through is the set §5.3 and §5.4 spell as JSON's own kinds: the numbers, the booleans and
     * text.
     */
    private void writeAtom(Object value, JsonDataEmitter out) {
        VocabularyAtoms.Entry vocabulary = vocabularyAtoms.get(value.getClass());
        if (vocabulary != null) {
            out.stringValue(vocabulary.write(value));
            return;
        }
        switch (value) {
            case Boolean b -> out.booleanValue(b);
            case Double d -> writeApproximate(d, d.isNaN(), d.isInfinite(), d > 0, out);
            case Float f -> writeApproximate(f, f.isNaN(), f.isInfinite(), f > 0, out);
            // Scale is part of the value §5.3 promises back, so a BigDecimal writes its own digits rather
            // than a canonicalised form: 199.90 stays 199.90.
            case BigDecimal decimal -> out.numberValue(decimal);
            case Number n -> out.numberValue(n.toString());
            case String s -> out.stringValue(s);
            case Character c -> out.stringValue(c.toString());
            default -> throw new WriteException(
                    "don't know how to write a value of type " + value.getClass() + " as JSON");
        }
    }

    /**
     * §5.4's approximate families, which are the only ones with a value JSON's number grammar cannot spell.
     *
     * <p>The two infinities and NaN are written as the JSON strings {@code ".inf"}/{@code "-.inf"}/{@code
     * ".nan"} -- [TSON-DATA] §7.6's own productions, so the parser that reads them back is the same one, and
     * no third spelling of infinity enters the series. Everything finite is an ordinary JSON number.
     */
    private void writeApproximate(Number value, boolean nan, boolean infinite, boolean positive,
                                  JsonDataEmitter out) {
        if (nan) {
            out.stringValue(".nan");
        } else if (infinite) {
            out.stringValue(positive ? ".inf" : "-.inf");
        } else {
            out.numberValue(value.toString());
        }
    }

    // ── Containers ───────────────────────────────────────────────────────

    /**
     * A record as a JSON object.
     *
     * <p>An absent field is left out rather than written {@code null}: §7 makes JSON null the absent
     * sentinel at a typed position, so the two say the same thing, and the shorter one is what a reader
     * of any strictness takes. The annotations carrier is skipped because it is not data -- see {@link
     * #write}.
     */
    private void writeRecord(Object value, DataClassRecord dataClass, JsonDataEmitter out) throws Throwable {
        DataClassField carrier = dataClass.annotationsCarrier().orElse(null);
        out.beginObject();
        for (DataClassField field : dataClass.fields()) {
            if (field == carrier || !field.isPresent(value)) {
                continue;
            }
            out.member(field.name());
            write(field.get(value), field.dataClass(), out);
        }
        out.endObject();
    }

    private void writeArray(Object value, DataClassArray dataClass, JsonDataEmitter out) throws Throwable {
        out.beginArray();
        int size = (int) dataClass.size().invoke(value);
        Object iterator = dataClass.iterator().invoke(value);
        DataClass elementClass = dataClass.arrayDataClass();
        for (int i = 0; i < size; i++) {
            write(dataClass.get().invoke(value, iterator), elementClass, out);
        }
        out.endArray();
    }

    /**
     * A map as a JSON object, its keys as member names.
     *
     * <p><b>A key must be an atom</b>, which is this module's reader's own rule stated from the other side:
     * a member name is a string, so a key type a name cannot spell has nowhere to go, and §6.5's pairs form
     * (which carries a compound key) is not read here. The name is the key's own text -- §5.1 hands a
     * string's content to the atom's parser, so a {@code UUID} key writes its {@code uuid} spelling and
     * reads back through the same one.
     */
    private void writeMap(Object value, DataClassMap dataClass, JsonDataEmitter out) throws Throwable {
        if (!(dataClass.keyDataClass() instanceof DataClassAtom)) {
            throw new WriteException(
                    ("a JSON object's member names are %s's keys, so its key type must be one a name can "
                            + "spell -- §6.5's pairs form, which carries a compound key, is not written here")
                            .formatted(dataClass.typeClass().getSimpleName()));
        }
        out.beginObject();
        Object iterator = dataClass.iterator().invoke(value);
        DataClass valueClass = dataClass.valueDataClass();
        Object entry;
        while ((entry = dataClass.next().invoke(iterator)) != null) {
            out.member(memberName(dataClass.key().invoke(entry), dataClass.keyDataClass()));
            write(dataClass.value().invoke(entry), valueClass, out);
        }
        out.endObject();
    }

    /** A key's text, through the same vocabulary a value would use, with any bridge applied first. */
    private String memberName(Object key, DataClass keyClass) throws Throwable {
        if (key == null) {
            throw new WriteException("a JSON member name cannot be absent, so a map with a null key "
                    + "has no JSON spelling");
        }
        Object wire = keyClass.bridge().isPresent() ? keyClass.bridge().get().toData().invoke(key) : key;
        VocabularyAtoms.Entry vocabulary = vocabularyAtoms.get(wire.getClass());
        return vocabulary != null ? vocabulary.write(wire) : String.valueOf(wire);
    }

    /**
     * A tuple as a JSON array.
     *
     * <p>Its tuple-ness is not recoverable from the document without a schema, exactly as an integer's
     * width is not: this writes a plain array, indistinguishable from an ordinary one, and a reader
     * recovers the shape from the position's own class.
     */
    private void writeTuple(Object value, DataClassTuple dataClass, JsonDataEmitter out) throws Throwable {
        out.beginArray();
        for (DataClassElement element : dataClass.elements()) {
            write(element.accessor().invoke(value), element.dataClass(), out);
        }
        out.endArray();
    }
}
