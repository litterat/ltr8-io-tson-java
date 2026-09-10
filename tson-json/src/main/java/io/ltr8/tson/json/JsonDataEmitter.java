package io.ltr8.tson.json;

import io.ltr8.tson.base.io.ByteSink;
import io.ltr8.tson.base.io.Utf8Sink;
import io.ltr8.tson.json.tree.JsonText;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Emits RFC 8259 JSON, one call per structural step -- the push-based write-direction peer of {@code
 * JsonStream}'s pull, and as agnostic of any Java object model as that is: this class knows objects,
 * arrays and the four leaf kinds, and nothing about what produced them.
 *
 * <p><b>It owns the separators and the indentation, and nothing else.</b> A caller says {@link
 * #beginObject()}, {@link #member} and {@link #endObject()}; the commas and colons between them, and the
 * newlines when this emitter is indenting, are this class's to place. That is the whole reason it exists
 * rather than each writer appending its own punctuation: two walks placing commas is two chances to place
 * one wrongly, and the failure is a document that will not parse.
 *
 * <p><b>Two sinks, one contract.</b> Over an {@link Appendable} it appends as it goes; over a {@link
 * ByteSink} it encodes UTF-8 itself ({@link Utf8Sink}) rather than through an {@code OutputStreamWriter} --
 * the write-side mirror of {@code JsonLexer} decoding UTF-8 off a {@code ByteSource} ([TSON-JSON] §3.1), and
 * what lets a document reach a {@code ByteBuffer} or a channel and not only an {@code OutputStream}.
 * <b>{@link #flush()} is required</b> on the byte path and does nothing on the other: bytes accumulate in a
 * block, and a document never flushed is a document never written.
 *
 * <p><b>No value is buffered.</b> A number is written from the literal it was given, so §5.3's digits and
 * scale survive a round trip -- {@code 199.90} is written {@code 199.90} and not {@code 199.9} -- and a
 * string is quoted by {@link JsonText}, the one place this module writes a string, so the reader's escape
 * decoding and this have a single inverse between them.
 */
public final class JsonDataEmitter {

    private final Appendable out;

    /**
     * The encoder this emitter owns, when it was built over a {@link ByteSink} -- {@code null} when it was
     * handed an {@code Appendable}, which is the caller's and has nothing here to push.
     */
    private final Utf8Sink encoder;

    /** {@code ""} for the compact form; otherwise one level's worth of indentation. */
    private final String indent;

    /** One entry per open object/array scope: how many members or elements written so far. */
    private final Deque<Integer> scopeCounts = new ArrayDeque<>();

    /** Accumulates into a {@link StringBuilder} this emitter owns -- see {@link #toString()}. */
    public JsonDataEmitter() {
        this(new StringBuilder());
    }

    /** Writes into {@code out} as it goes, appending nothing this class does not immediately emit. */
    public JsonDataEmitter(Appendable out) {
        this(out, "");
    }

    /** Writes into {@code sink} as it goes, encoding UTF-8 itself; {@link #flush()} is required. */
    public JsonDataEmitter(ByteSink sink) {
        this(sink, "");
    }

    /**
     * Indenting by {@code indent} per level, one member or element to a line -- {@code ""} for the compact
     * form, which is the one to send ([TSON-JSON] §9.3 makes a round trip a value question, and whitespace
     * is not a value).
     */
    public JsonDataEmitter(Appendable out, String indent) {
        this.out = out;
        this.encoder = null;
        this.indent = indent;
    }

    /** {@link #JsonDataEmitter(Appendable, String)} over a byte sink; {@link #flush()} is required. */
    public JsonDataEmitter(ByteSink sink, String indent) {
        this.encoder = Utf8Sink.over(sink);
        this.out = encoder;
        this.indent = indent;
    }

    /**
     * Pushes anything this emitter buffered to its sink. A no-op for an {@code Appendable}, which holds
     * whatever it was given the moment it is given it.
     */
    public void flush() {
        if (encoder != null) {
            encoder.flush();
        }
    }

    // ── Objects ──────────────────────────────────────────────────────────

    public JsonDataEmitter beginObject() {
        beforeValue();
        emit('{');
        scopeCounts.push(0);
        return this;
    }

    public JsonDataEmitter endObject() {
        close('}');
        return this;
    }

    /**
     * A member name, which is a JSON string like any other -- §3.2 gives the name no vocabulary of its own,
     * so the same quoting applies and a name needing an escape gets one.
     */
    public JsonDataEmitter member(String name) {
        countAndSeparate();
        newlineAndIndent();
        emit(JsonText.quote(name));
        emit(':');
        if (!indent.isEmpty()) {
            emit(' ');
        }
        expectingMemberValue = true;
        return this;
    }

    // ── Arrays ───────────────────────────────────────────────────────────

    public JsonDataEmitter beginArray() {
        beforeValue();
        emit('[');
        scopeCounts.push(0);
        return this;
    }

    public JsonDataEmitter endArray() {
        close(']');
        return this;
    }

    // ── The four leaf kinds ──────────────────────────────────────────────

    /** RFC 8259's string, quoted and escaped by {@link JsonText}. */
    public JsonDataEmitter stringValue(String text) {
        beforeValue();
        emit(JsonText.quote(text));
        return this;
    }

    /**
     * A number from the literal that denotes it, written through unchanged.
     *
     * <p>Taking the literal rather than a {@code double} is [TSON-JSON] §5.3's requirement, not an
     * ergonomic choice: a JSON number's digits and scale are part of what a round trip must preserve, and
     * every host type that reaches here has already decided how it spells itself.
     */
    public JsonDataEmitter numberValue(String literal) {
        beforeValue();
        emit(literal);
        return this;
    }

    /** {@link #numberValue(String)} at full precision, scale included -- {@code 199.90} stays {@code 199.90}. */
    public JsonDataEmitter numberValue(BigDecimal value) {
        return numberValue(value.toPlainString());
    }

    public JsonDataEmitter numberValue(long value) {
        return numberValue(Long.toString(value));
    }

    public JsonDataEmitter booleanValue(boolean value) {
        beforeValue();
        emit(value ? "true" : "false");
        return this;
    }

    /**
     * RFC 8259's {@code null}.
     *
     * <p>At this layer it is a value and nothing more. [TSON-JSON] §7 makes it the absent sentinel's
     * spelling <em>at a typed position</em>, which is the schema-directed decode's rule and not one a
     * structural emitter can apply -- it does not know what position anything is at.
     */
    public JsonDataEmitter nullValue() {
        beforeValue();
        emit("null");
        return this;
    }

    // ── Separation ───────────────────────────────────────────────────────

    /**
     * Places the comma, the newline and the indentation a value needs before it, and counts it against the
     * scope holding it.
     *
     * <p><b>A member's value is the one that needs none of it</b>: {@link #member} has just placed all
     * three for the pair, and a comma between a name and its own value is a document that will not parse.
     * That is what {@link #expectingMemberValue} says, and it is one flag rather than a rule each leaf
     * method has to remember.
     */
    private void beforeValue() {
        if (expectingMemberValue) {
            expectingMemberValue = false;
            return;
        }
        if (scopeCounts.isEmpty()) {
            return;      // the document's root value: nothing encloses it and nothing precedes it
        }
        countAndSeparate();
        newlineAndIndent();
    }

    private void countAndSeparate() {
        int written = scopeCounts.pop();
        if (written > 0) {
            emit(',');
        }
        scopeCounts.push(written + 1);
    }

    /** Set by {@link #member}, cleared by the {@link #beforeValue} of the value that member holds. */
    private boolean expectingMemberValue;

    private void newlineAndIndent() {
        if (indent.isEmpty()) {
            return;
        }
        emit('\n');
        emit(indent.repeat(scopeCounts.size()));
    }

    private void close(char bracket) {
        int written = scopeCounts.pop();
        if (!indent.isEmpty() && written > 0) {
            emit('\n');
            emit(indent.repeat(scopeCounts.size()));
        }
        emit(bracket);
    }

    // ── Output ───────────────────────────────────────────────────────────

    /**
     * <b>The sink's failure is an IO fault, never a statement about the value being written</b>, so it
     * arrives as {@link UncheckedIOException} and the emitter stays free of checked exceptions -- a {@link
     * StringBuilder} sink never throws one at all.
     */
    private void emit(CharSequence text) {
        try {
            out.append(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** As {@link #emit(CharSequence)}, for the single characters the delimiters are. */
    private void emit(char c) {
        try {
            out.append(c);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The document written so far, when this emitter owns its own buffer (the no-argument constructor).
     * With a caller-supplied sink this is the sink's own {@code toString}, which is rarely the text -- a
     * streaming caller reads its own stream, not this.
     */
    @Override
    public String toString() {
        return out.toString();
    }
}
