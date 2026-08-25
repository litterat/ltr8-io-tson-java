package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.atom.AtomTypeException;
import io.ltr8.tson.compiler.atom.UriParser;
import io.ltr8.tson.compiler.ast.TokenForm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.Deque;

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

    /** Whether a {@link #typeRef} has been written for the value now being written -- see that method. */
    private boolean typeRefPending;
    private String pendingTypeRef;

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

    // ── Header directives (§2.2, §3.3) ──────────────────────────────────────

    /**
     * {@code !!id:"<uri>"} and its line terminator -- the document's own identity, and the <b>first</b> line
     * when present (§2.2), so a caller writing both directives calls this one first.
     *
     * <p>The terminator is not cosmetic: §2.2.1 bounds the content-hash input at the id line's own
     * terminator, so an {@code !!id} that shares a line with what follows has no defined hash.
     */
    public TsonDataEmitter documentId(String uri) {
        return directive("id", uri);
    }

    /**
     * {@code !!schema:"<uri>"} and its line terminator -- the schema governing the value that follows, which
     * is what makes a data document self-describing: a reader resolves this, validates against it, and needs
     * nothing out of band.
     *
     * <p>Legal in a document header and at a scoped-value position (§3.3); this emits it wherever the
     * caller is, exactly like every other method here.
     */
    public TsonDataEmitter schemaRef(String uri) {
        return directive("schema", uri);
    }

    /**
     * One directive, terminated by a newline. Private because the name set is closed and positional (§3.3):
     * of the four, only {@code id} and {@code schema} may appear in a <em>data</em> document, and those are
     * the two methods above. {@code meta}/{@code import} belong to a schema document, which this emitter
     * does not write.
     *
     * <p>{@code uri} is checked against the same atom the reader parses it with, so a caller cannot emit a
     * document that will not read back -- a directive argument MUST be a URI (§3.3), and the failure belongs
     * at the write that caused it rather than at whoever reads the result.
     */
    private TsonDataEmitter directive(String name, String uri) {
        try {
            UriParser.UNCONSTRAINED.read(new TokenValue(uri, TokenForm.SINGLE_LINE_QUOTED));
        } catch (AtomTypeException e) {
            throw new TsonWriteException("'!!" + name + "' argument \"" + uri + "\" is not a valid URI (§3.3): "
                    + e.getMessage(), e);
        }
        emit("!!");
        emit(name);
        emit(':');
        quotedString(uri);
        emit('\n');
        return this;
    }

    // ── Type annotations (§3.2) ─────────────────────────────────────────────

    /**
     * {@code !name }, adjacent to {@code name} per §3.2, one trailing space before the value.
     *
     * <p><b>At most one per value</b>, which this enforces: {@code data-value = *annotation [type-ref]
     * core-value} admits exactly one, and a second is a parse error in the document that results -- so a
     * caller declaring a root type for a value that writes its own (a vocabulary host type, a union member)
     * finds out here rather than at whoever tries to read it. The pending flag clears the moment a
     * core-value starts, so a nested value's own type-ref is unaffected, and so is an annotation's.
     */
    public TsonDataEmitter typeRef(String name) {
        if (typeRefPending) {
            throw new TsonWriteException("two type annotations on one value ('!" + pendingTypeRef + "' then '!"
                    + name + "'): §3.2 admits at most one, so the result would not parse", null);
        }
        typeRefPending = true;
        pendingTypeRef = name;
        emit('!');
        emit(name);
        emit(' ');
        return this;
    }

    // ── Leaf tokens ──────────────────────────────────────────────────────────

    /** {@code null}, the base type (§4.1) -- distinct from {@link #absentValue()}. */
    public TsonDataEmitter nullValue() {
        startCoreValue();
        emit("null");
        return this;
    }

    /** {@code _}, the absent sentinel (§2.9) -- distinct from {@link #nullValue()}. */
    public TsonDataEmitter absentValue() {
        startCoreValue();
        emit('_');
        return this;
    }

    public TsonDataEmitter booleanValue(boolean value) {
        startCoreValue();
        emit(value ? "true" : "false");
        return this;
    }

    /**
     * Writes {@code text} as-is, unquoted -- the caller is responsible for {@code text} already
     * being valid unquoted-token content (a plain number's digits, an enum's {@code name()}, ...).
     * Never used for arbitrary strings; see {@link #quotedString(String)} for those.
     */
    public TsonDataEmitter unquotedToken(String text) {
        startCoreValue();
        emit(text);
        return this;
    }

    /**
     * A C0 control character, the range §7.2.2 requires escaped. <b>A comparison, deliberately, not a
     * {@code Pattern}</b> -- this runs once per character of every string written, and a regex here cost a
     * {@code String}, a {@code Matcher} and the {@code Matcher}'s own internals per character (measured at
     * 56 bytes against 0) to answer what one comparison answers. {@code AllocationHarnessTest} pins it.
     */
    private static boolean isControl(char c) {
        return c <= 0x1f;
    }

    /**
     * Writes {@code text} as a quoted, escaped single-line string token (§7.2.2). Escapes exactly
     * what must be escaped for the result to lex back to the same text -- {@code "}, {@code \}, and
     * C0 control characters (named escapes where the lexer recognises one, {@code \\uXXXX}
     * otherwise) -- and leaves everything else, including non-ASCII text, literal.
     */
    public TsonDataEmitter quotedString(String text) {
        startCoreValue();
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
                    if (isControl(c)) {
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

    /**
     * Writes {@code text} as a multi-line string token (§7.2.3) -- the third token form, alongside {@link
     * #unquotedToken} and {@link #quotedString}.
     *
     * <p><b>Emitted with no common prefix at all</b>: the closing {@code """} sits at column 0 and every
     * content line is written with its own leading whitespace and nothing else. §7.2.3 computes the prefix
     * to strip from the closing delimiter's indent narrowed by each non-blank line's, so an empty closing
     * indent makes the prefix empty, every line's own indentation part of the value, and the one case the
     * §7.2.3 handles by removing only the matching portion -- a blank line shorter than the computed prefix --
     * unreachable rather than relied on.
     *
     * <p><b>What has to be escaped is decided by the reading order</b>, which strips trailing whitespace
     * from each line and only then decodes escapes (§7.2.3 rule 5). So a line's trailing spaces or tabs are
     * written as a {@code \\uXXXX} escape: it survives the strip and decodes back to the space. The same
     * ordering is why a backslash must be doubled. A line that would otherwise begin {@code """} has its
     * first quote escaped, since the reader would take it for the closing delimiter and end the token
     * early. Everything else, non-ASCII included, is written literally -- the whole point of this form.
     *
     * <p>The result lexes back to exactly {@code text}, whatever it contains.
     */
    public TsonDataEmitter multiLineString(String text) {
        startCoreValue();
        emit("\"\"\"\n");
        for (String line : text.split("\n", -1)) {
            emit(escapeMultiLine(line));
            emit('\n');
        }
        emit("\"\"\"");
        return this;
    }

    /** One content line of a multi-line token, escaped so §7.2.3's own reading order returns it unchanged. */
    private static String escapeMultiLine(String line) {
        int trailing = line.length();
        while (trailing > 0 && (line.charAt(trailing - 1) == ' ' || line.charAt(trailing - 1) == '\t')) {
            trailing--;
        }
        StringBuilder out = new StringBuilder(line.length());
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            boolean isTrailingBlank = i >= trailing;
            if (c == '\\') {
                out.append("\\\\");
            } else if (isTrailingBlank || c == '\r' || isControl(c)) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        // A line the reader would mistake for the closing delimiter: `"""` after its leading whitespace.
        String content = out.toString();
        int indent = 0;
        while (indent < content.length() && (content.charAt(indent) == ' ' || content.charAt(indent) == '\t')) {
            indent++;
        }
        if (content.startsWith("\"\"\"", indent)) {
            return content.substring(0, indent) + "\\u0022" + content.substring(indent + 1);
        }
        return content;
    }

    // ── Scope bookkeeping ────────────────────────────────────────────────────

    private TsonDataEmitter open(char delimiter) {
        startCoreValue();
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
     * A core-value is starting, so whatever type-ref preceded it belongs to it and is spent -- the whole of
     * {@link #typeRef}'s at-most-one bookkeeping. Every method that writes a value's own first character
     * calls this, which is what keeps a nested value's type-ref (or an annotation's) independent of the
     * value enclosing it.
     */
    private void startCoreValue() {
        typeRefPending = false;
        pendingTypeRef = null;
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
