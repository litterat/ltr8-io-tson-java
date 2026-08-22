package io.ltr8.tson.compiler;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Pattern;

/**
 * Builds TSON source text incrementally -- the write-side counterpart to {@link io.ltr8.tson.compiler.lexer.Lexer}/{@link
 * TsonDataParser}'s read side, and just as agnostic of any particular Java object model: this class knows
 * TSON's own grammar (delimiters, separators, escaping) and nothing about {@code DataClass} or
 * any bound Java type. {@link TsonObjectWriter} is the layer that walks a Java object graph and drives
 * this writer, the same relationship {@link TsonObjectReader} has with {@code TsonDataParser}'s output on the read side.
 *
 * <p><b>Separation, not commas.</b> Confirmed against §2.4 and this repo's own test literals: TSON
 * never requires a comma between sibling elements -- "zero-width separation is a parse error", not
 * "a comma is required" (see {@code TsonDataStream.consumeSeparatorOrCloseCheck}, which accepts
 * either a comma or a whitespace gap as the required separator). This writer always inserts a
 * single space before every element (including the first, right after an opening delimiter) and
 * before a non-empty scope's closing delimiter -- {@code { x: 1 y: 2 }}, not {@code {x: 1, y: 2}}
 * -- valid either way, but matching this repo's own established literal style.
 *
 * <p><b>Writes into an {@link Appendable} sink, which is what keeps a document off the heap.</b> The
 * no-argument constructor supplies its own {@link StringBuilder} -- the whole document in memory, which is
 * all a {@code toTson} caller wants -- while a {@code Writer} over an {@code OutputStream} lets the bytes
 * leave as they are produced. Nothing here buffers on its own: every method appends straight to the sink, so
 * memory is the sink's business plus this class's own scope stack. An {@link IOException} from the sink
 * becomes an {@link UncheckedIOException}, the same treatment {@code Lexer} gives a failing {@code
 * InputStream} on the read side.
 *
 * <p>Not thread-safe; single-use, like {@link io.ltr8.tson.compiler.lexer.Lexer}.
 */
public final class TsonDataEmitter {

    private final Appendable out;

    /** Accumulates into a {@link StringBuilder} this emitter owns -- see {@link #toString()}. */
    public TsonDataEmitter() {
        this(new StringBuilder());
    }

    /** Writes into {@code out} as it goes, appending nothing this class does not immediately emit. */
    public TsonDataEmitter(Appendable out) {
        this.out = out;
    }

    /** One entry per open record/map/array scope: how many elements written so far. */
    private final Deque<Integer> scopeElementCounts = new ArrayDeque<>();

    private static final Pattern CONTROL_CHAR = Pattern.compile("[\\x00-\\x1f]");

    // ── Records and maps (both "{" "}", differing only in entry shape) ─────

    public TsonDataEmitter beginRecord() {
        return open('{');
    }

    public TsonDataEmitter endRecord() {
        return close('}');
    }

    public TsonDataEmitter beginMap() {
        return open('{');
    }

    public TsonDataEmitter endMap() {
        return close('}');
    }

    /** {@code name:} -- inserts the inter-element separator itself; the value follows directly. */
    public TsonDataEmitter field(String name) {
        beforeElement();
        emit(name);
        emit(':');
        emit(' ');
        return this;
    }

    /** Call before writing a map entry's key (itself a full data-value, §2.6). */
    public TsonDataEmitter beforeMapEntry() {
        beforeElement();
        return this;
    }

    /** {@code =>} between a map entry's key and value, once the key has been written. */
    public TsonDataEmitter mapArrow() {
        emit(" => ");
        return this;
    }

    // ── Arrays (also used for tuples -- same "[" "]" shape, §2.7) ───────────

    public TsonDataEmitter beginArray() {
        return open('[');
    }

    public TsonDataEmitter endArray() {
        return close(']');
    }

    /** Call before writing each array/tuple element. */
    public TsonDataEmitter beforeArrayElement() {
        beforeElement();
        return this;
    }

    // ── Annotations (§3.1) ──────────────────────────────────────────────────

    /**
     * {@code @name } -- a valueless annotation. <b>The trailing space is required, not cosmetic:</b>
     * §3.1 makes the single character after the name the whole of the boundary rule, so with no
     * {@code ":"} at least one whitespace character MUST follow, or the name runs into whatever
     * comes next.
     */
    public TsonDataEmitter annotation(String name) {
        emit('@');
        emit(name);
        emit(' ');
        return this;
    }

    /**
     * {@code @name:} -- opens an annotation carrying a value; the caller writes exactly one
     * data-value next, then calls {@link #endAnnotation()}. The {@code ":"} is emitted adjacent to
     * the name as §7.5 requires, and nothing follows it here: whitespace after the colon is
     * optional, and omitting it keeps the common {@code @doc:"..."} form compact.
     */
    public TsonDataEmitter beginAnnotation(String name) {
        emit('@');
        emit(name);
        emit(':');
        return this;
    }

    /**
     * Closes the annotation opened by {@link #beginAnnotation}: a single space separating its value
     * from whatever follows -- a sibling annotation, the type-ref, or the annotated value's own
     * core-value. An annotation's value terminates at the end of its own core value (§3.1), so
     * separation is all that is needed; there is no closing delimiter to match.
     */
    public TsonDataEmitter endAnnotation() {
        emit(' ');
        return this;
    }

    // ── Type annotations (§3.2) ─────────────────────────────────────────────

    /** {@code !name }, adjacent to {@code name} per §3.2, one trailing space before the value. */
    public TsonDataEmitter typeRef(String name) {
        emit('!');
        emit(name);
        emit(' ');
        return this;
    }

    // ── Leaf tokens ──────────────────────────────────────────────────────────

    /** {@code null}, the base type (§4.1) -- distinct from {@link #absentValue()}. */
    public TsonDataEmitter nullValue() {
        emit("null");
        return this;
    }

    /** {@code _}, the absent sentinel (§2.9) -- distinct from {@link #nullValue()}. */
    public TsonDataEmitter absentValue() {
        emit('_');
        return this;
    }

    public TsonDataEmitter booleanValue(boolean value) {
        emit(value ? "true" : "false");
        return this;
    }

    /**
     * Writes {@code text} as-is, unquoted -- the caller is responsible for {@code text} already
     * being valid unquoted-token content (a plain number's digits, an enum's {@code name()}, ...).
     * Never used for arbitrary strings; see {@link #quotedString(String)} for those.
     */
    public TsonDataEmitter unquotedToken(String text) {
        emit(text);
        return this;
    }

    /**
     * Writes {@code text} as a quoted, escaped single-line string token (§7.2.2). Escapes exactly
     * what must be escaped for the result to lex back to the same text -- {@code "}, {@code \}, and
     * C0 control characters (named escapes where the lexer recognises one, {@code \\uXXXX}
     * otherwise) -- and leaves everything else, including non-ASCII text, literal.
     */
    public TsonDataEmitter quotedString(String text) {
        emit('"');
        int length = text.length();
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> emit("\\\"");
                case '\\' -> emit("\\\\");
                case '\b' -> emit("\\b");
                case '\f' -> emit("\\f");
                case '\n' -> emit("\\n");
                case '\r' -> emit("\\r");
                case '\t' -> emit("\\t");
                default -> {
                    if (CONTROL_CHAR.matcher(String.valueOf(c)).matches()) {
                        emit(String.format("\\u%04x", (int) c));
                    } else {
                        emit(c);
                    }
                }
            }
        }
        emit('"');
        return this;
    }

    // ── Scope bookkeeping ────────────────────────────────────────────────────

    private TsonDataEmitter open(char delimiter) {
        emit(delimiter);
        scopeElementCounts.push(0);
        return this;
    }

    private TsonDataEmitter close(char delimiter) {
        int count = scopeElementCounts.pop();
        if (count > 0) {
            emit(' ');
        }
        emit(delimiter);
        return this;
    }

    /**
     * Inserts the separator before an element of the *currently open* scope -- a no-op at the top
     * level (no enclosing scope yet), otherwise always a single space, whether this is the first
     * element or a later one (the opening delimiter provides no space of its own).
     */
    private void beforeElement() {
        if (!scopeElementCounts.isEmpty()) {
            emit(' ');
            scopeElementCounts.push(scopeElementCounts.pop() + 1);
        }
    }

    /**
     * Appends to the sink, turning its checked {@link IOException} into an {@link UncheckedIOException} so
     * the whole emitter stays free of checked exceptions -- a {@link StringBuilder} sink never throws one at
     * all, and a stream sink's failure is an IO fault rather than anything about the value being written.
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
