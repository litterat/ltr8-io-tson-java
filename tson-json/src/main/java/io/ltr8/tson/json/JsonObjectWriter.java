package io.ltr8.tson.json;

import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.io.ByteSink;
import io.ltr8.tson.json.writer.DataClassObjectWriter;

import java.io.OutputStream;

/**
 * Writes a Java object out as JSON -- the inverse of {@link JsonObjectReader}, and the JSON peer of
 * {@code TsonObjectWriter}.
 *
 * <p><b>The class is the schema, in both directions.</b> [TSON-JSON] §4.1 makes reading schema-directed and
 * this module's reader lets the target class play that part; this is that arrangement from the other end,
 * so what a class reads is what it writes. A record is an object, a {@code List} an array, a {@code Map} an
 * object keyed by its keys' own text, a tuple an array.
 *
 * <p><b>What does not survive is what the class does not hold.</b> An integer's width and a tuple's
 * tuple-ness are the position's, recovered by whatever class reads the document back, so a document written
 * here round-trips through the same class and is not self-describing without one.
 *
 * <p><b>Annotations have no JSON carrier, so a value carrying them is refused</b> ([TSON-JSON] §4.3): a
 * record whose {@code Annotations} carrier is non-empty, or an annotated box with a non-empty annotation
 * set, is a {@code WriteException} naming the position and the annotations. {@link #strippingAnnotations()}
 * is §4.3's explicit configuration, under which they are dropped and the value written without them. An
 * empty carrier is never an error, so a class with a carrier writes without configuration whenever it holds
 * nothing -- which is what this module's reader fills every carrier with.
 *
 * <p><b>Three things are refused rather than approximated</b>: annotations, above, and two more because a
 * reader could not take them back -- a choice, which JSON has no schemaless way to discriminate (§8.2), and a
 * host value with no JSON spelling. Non-finite {@code double}s are not among them -- §5.4's approximate
 * families spell them as the strings {@code ".inf"}/{@code "-.inf"}/{@code ".nan"}, which read back through
 * the same parser.
 *
 * <p><b>Every sink is UTF-8 and none of them is closed here.</b> {@code write(value, ByteSink)} encodes
 * UTF-8 itself, so a document reaches a {@code ByteBuffer}, a channel or a file without existing as a
 * {@code String} first; each {@code write} flushes what it emitted, because closing is the caller's.
 */
public final class JsonObjectWriter {

    private final DataBindContext context;
    private final DataClassObjectWriter engine;
    private final String indent;
    private final boolean stripAnnotations;

    /** Over {@code context}'s bindings. */
    public static JsonObjectWriter using(DataBindContext context) {
        return new JsonObjectWriter(context, "", false);
    }

    /**
     * Over the default bindings -- the JDK scalars and the host types the built-in atom vocabulary reads to
     * ({@code AtomContext.defaultContext()}), which is what {@link JsonObjectReader#standard()} reads into.
     */
    public static JsonObjectWriter standard() {
        return new JsonObjectWriter(AtomContext.defaultContext(), "", false);
    }

    private JsonObjectWriter(DataBindContext context, String indent, boolean stripAnnotations) {
        this.context = context;
        this.engine = new DataClassObjectWriter(context, stripAnnotations);
        this.indent = indent;
        this.stripAnnotations = stripAnnotations;
    }

    /**
     * This writer indenting by {@code indent} per level -- a new writer, leaving this one unchanged.
     * {@code ""} restores the compact form, which is the default and the one to send.
     */
    public JsonObjectWriter indented(String indent) {
        return new JsonObjectWriter(context, indent, stripAnnotations);
    }

    /** {@link #indented(String)} at two spaces, the shape most JSON is read in. */
    public JsonObjectWriter indented() {
        return indented("  ");
    }

    /**
     * This writer dropping annotations rather than refusing them -- a new writer, leaving this one unchanged.
     * [TSON-JSON] §4.3 refuses a value that carries annotations, since JSON has nowhere to put them, and admits
     * stripping only as an explicit code-level configuration; this is that configuration. The value is written
     * as though its annotation sets were empty.
     */
    public JsonObjectWriter strippingAnnotations() {
        return new JsonObjectWriter(context, indent, true);
    }

    /** The bindings this writer writes through. */
    public DataBindContext dataBindContext() {
        return context;
    }

    /** {@code value} as JSON text. */
    public String toJson(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    /** Writes {@code value} to {@code out} as UTF-8, flushed. {@code out} is not closed here. */
    public void write(Object value, OutputStream out) {
        write(value, ByteSink.of(out));
    }

    /**
     * Writes {@code value} to {@code sink} as UTF-8, flushed. {@code sink} is not closed here -- whoever
     * created it closes it, and this writer created nothing.
     */
    public void write(Object value, ByteSink sink) {
        JsonDataEmitter emitter = new JsonDataEmitter(sink, indent);
        engine.write(value, emitter);
        emitter.flush();
    }

    /** Writes {@code value} to {@code out}, which holds characters and so needs no encoding. */
    public void write(Object value, Appendable out) {
        engine.write(value, new JsonDataEmitter(out, indent));
    }
}
