package io.ltr8.tson.json;

import io.ltr8.tson.base.io.ByteSink;
import io.ltr8.tson.json.tree.JsonValue;
import io.ltr8.tson.json.writer.TreeValueWriter;

import java.io.OutputStream;

/**
 * Writes a {@link JsonValue} tree back out as JSON -- the inverse of {@link JsonTreeReader}, and the JSON
 * peer of {@code TsonTreeWriter}.
 *
 * <p><b>The round trip is total, which is what separates this from its TSON counterpart.</b> {@code
 * TsonTreeWriter} has documented losses (integer width, tuple-ness) because TSON text spells one value
 * several ways and a tree does not record which; RFC 8259 has six kinds and one spelling each, and the
 * tree holds the literal a number was read from, so a document read by {@code JsonTreeReader} and written
 * here is the same document. The only thing not preserved is whitespace, which [TSON-JSON] §9.3 does not
 * make part of a value.
 *
 * <p><b>Derivations, not settings.</b> {@link #indented(String)} returns a new writer, leaving this one
 * unchanged, so a writer may be handed out and derived from -- the shape every reader in this library
 * takes. The default is the compact form, which is the one to send: §9.3 makes a round trip a question
 * about values, and a document that is read once and never inspected by a person should not pay bytes to
 * be pretty.
 *
 * <p><b>Every sink is UTF-8 and none of them is closed here.</b> {@code write(value, ByteSink)} encodes
 * UTF-8 itself rather than through an {@code OutputStreamWriter}, so a document reaches a {@code
 * ByteBuffer}, a channel or a file without existing as a {@code String} first; each {@code write} flushes
 * what it emitted, because closing is the caller's and a sink cannot tell a caller who finished from one
 * who abandoned the write.
 */
public final class JsonTreeWriter {

    private static final TreeValueWriter ENGINE = new TreeValueWriter();

    private final String indent;

    public JsonTreeWriter() {
        this("");
    }

    private JsonTreeWriter(String indent) {
        this.indent = indent;
    }

    /**
     * This writer indenting by {@code indent} per level, one member or element to a line -- a new writer,
     * leaving this one unchanged. {@code ""} restores the compact form.
     *
     * <p>{@code JsonValue.toDisplayString(indent)} is the same rendering reached from a value rather than
     * from a writer, and is JEP 540's spelling of it; this is the one that reaches a sink.
     */
    public JsonTreeWriter indented(String indent) {
        return new JsonTreeWriter(indent);
    }

    /** {@link #indented(String)} at two spaces, the shape most JSON is read in. */
    public JsonTreeWriter indented() {
        return indented("  ");
    }

    /** {@code value} as JSON text. */
    public String toJson(JsonValue value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    /** Writes {@code value} to {@code out} as UTF-8, flushed. {@code out} is not closed here. */
    public void write(JsonValue value, OutputStream out) {
        write(value, ByteSink.of(out));
    }

    /**
     * Writes {@code value} to {@code sink} as UTF-8, flushed. {@code sink} is not closed here -- whoever
     * created it closes it, and this writer created nothing.
     */
    public void write(JsonValue value, ByteSink sink) {
        JsonDataEmitter emitter = new JsonDataEmitter(sink, indent);
        ENGINE.write(value, emitter);
        emitter.flush();
    }

    /** Writes {@code value} to {@code out}, which holds characters and so needs no encoding. */
    public void write(JsonValue value, Appendable out) {
        ENGINE.write(value, new JsonDataEmitter(out, indent));
    }
}
