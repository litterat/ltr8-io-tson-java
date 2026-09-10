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
 * here round-trips through the same class and is not self-describing without one. Wire annotations are
 * dropped: §3.3's annotation object belongs to the schema-directed decode, and this module's reader binds
 * every carrier to {@code Annotations.empty()}, so writing them would emit members no reader here takes
 * back.
 *
 * <p><b>Two things are refused rather than approximated</b>, both because a reader could not take them
 * back: a choice, which JSON has no schemaless way to discriminate (§8.2), and a host value with no JSON
 * spelling. Non-finite {@code double}s are not among them -- §5.4's approximate families spell them as the
 * strings {@code ".inf"}/{@code "-.inf"}/{@code ".nan"}, which read back through the same parser.
 *
 * <p><b>Every sink is UTF-8 and none of them is closed here.</b> {@code write(value, ByteSink)} encodes
 * UTF-8 itself, so a document reaches a {@code ByteBuffer}, a channel or a file without existing as a
 * {@code String} first; each {@code write} flushes what it emitted, because closing is the caller's.
 */
public final class JsonObjectWriter {

    private final DataBindContext context;
    private final DataClassObjectWriter engine;
    private final String indent;

    /** Over {@code context}'s bindings. */
    public static JsonObjectWriter using(DataBindContext context) {
        return new JsonObjectWriter(context, "");
    }

    /**
     * Over the default bindings -- the JDK scalars and the host types the built-in atom vocabulary reads to
     * ({@code AtomContext.defaultContext()}), which is what {@link JsonObjectReader#standard()} reads into.
     */
    public static JsonObjectWriter standard() {
        return new JsonObjectWriter(AtomContext.defaultContext(), "");
    }

    private JsonObjectWriter(DataBindContext context, String indent) {
        this.context = context;
        this.engine = new DataClassObjectWriter(context);
        this.indent = indent;
    }

    /**
     * This writer indenting by {@code indent} per level -- a new writer, leaving this one unchanged.
     * {@code ""} restores the compact form, which is the default and the one to send.
     */
    public JsonObjectWriter indented(String indent) {
        return new JsonObjectWriter(context, indent);
    }

    /** {@link #indented(String)} at two spaces, the shape most JSON is read in. */
    public JsonObjectWriter indented() {
        return indented("  ");
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
