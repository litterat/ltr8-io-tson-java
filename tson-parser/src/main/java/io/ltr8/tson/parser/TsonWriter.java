package io.ltr8.tson.parser;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Pattern;

/**
 * Builds TSON source text incrementally -- the write-side counterpart to {@link Lexer}/{@link
 * Parser}'s read side, and just as agnostic of any particular Java object model: this class knows
 * TSON's own grammar (delimiters, separators, escaping) and nothing about {@code DataClass} or
 * any bound Java type. {@code TsonMapper} is the layer that walks a Java object graph and drives
 * this writer, the same relationship it already has with {@code Parser}'s output on the read side.
 *
 * <p><b>Separation, not commas.</b> Confirmed against §2.4 and this repo's own test literals: TSON
 * never requires a comma between sibling elements -- "zero-width separation is a parse error", not
 * "a comma is required" (`Parser.consumeSeparatorOrCloseCheck`'s own doc: "a real comma token is
 * optional evidence, a position gap is the other kind of evidence"). This writer always inserts a
 * single space before every element (including the first, right after an opening delimiter) and
 * before a non-empty scope's closing delimiter -- {@code { x: 1 y: 2 }}, not {@code {x: 1, y: 2}}
 * -- valid either way, but matching this repo's own established literal style.
 *
 * <p>Not thread-safe; single-use, like {@link Lexer}.
 */
public final class TsonWriter {

    private final StringBuilder out = new StringBuilder();

    /** One entry per open record/map/array scope: how many elements written so far. */
    private final Deque<Integer> scopeElementCounts = new ArrayDeque<>();

    private static final Pattern CONTROL_CHAR = Pattern.compile("[\\x00-\\x1f]");

    // ── Records and maps (both "{" "}", differing only in entry shape) ─────

    public TsonWriter beginRecord() {
        return open('{');
    }

    public TsonWriter endRecord() {
        return close('}');
    }

    public TsonWriter beginMap() {
        return open('{');
    }

    public TsonWriter endMap() {
        return close('}');
    }

    /** {@code name:} -- inserts the inter-element separator itself; the value follows directly. */
    public TsonWriter field(String name) {
        beforeElement();
        out.append(name).append(':').append(' ');
        return this;
    }

    /** Call before writing a map entry's key (itself a full data-value, §2.6). */
    public TsonWriter beforeMapEntry() {
        beforeElement();
        return this;
    }

    /** {@code =>} between a map entry's key and value, once the key has been written. */
    public TsonWriter mapArrow() {
        out.append(" => ");
        return this;
    }

    // ── Arrays (also used for tuples -- same "[" "]" shape, §2.7) ───────────

    public TsonWriter beginArray() {
        return open('[');
    }

    public TsonWriter endArray() {
        return close(']');
    }

    /** Call before writing each array/tuple element. */
    public TsonWriter beforeArrayElement() {
        beforeElement();
        return this;
    }

    // ── Type annotations (§3.2) ─────────────────────────────────────────────

    /** {@code !name }, adjacent to {@code name} per §3.2, one trailing space before the value. */
    public TsonWriter typeRef(String name) {
        out.append('!').append(name).append(' ');
        return this;
    }

    // ── Leaf tokens ──────────────────────────────────────────────────────────

    /** {@code null}, the base type (§4.1) -- distinct from {@link #absentValue()}. */
    public TsonWriter nullValue() {
        out.append("null");
        return this;
    }

    /** {@code _}, the absent sentinel (§2.9) -- distinct from {@link #nullValue()}. */
    public TsonWriter absentValue() {
        out.append('_');
        return this;
    }

    public TsonWriter booleanValue(boolean value) {
        out.append(value ? "true" : "false");
        return this;
    }

    /**
     * Writes {@code text} as-is, unquoted -- the caller is responsible for {@code text} already
     * being valid unquoted-token content (a plain number's digits, an enum's {@code name()}, ...).
     * Never used for arbitrary strings; see {@link #quotedString(String)} for those.
     */
    public TsonWriter unquotedToken(String text) {
        out.append(text);
        return this;
    }

    /**
     * Writes {@code text} as a quoted, escaped single-line string token (§7.2.2). Escapes exactly
     * what must be escaped for the result to lex back to the same text -- {@code "}, {@code \}, and
     * C0 control characters (named escapes where the lexer recognises one, {@code \\uXXXX}
     * otherwise) -- and leaves everything else, including non-ASCII text, literal.
     */
    public TsonWriter quotedString(String text) {
        out.append('"');
        int length = text.length();
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (CONTROL_CHAR.matcher(String.valueOf(c)).matches()) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
        return this;
    }

    // ── Scope bookkeeping ────────────────────────────────────────────────────

    private TsonWriter open(char delimiter) {
        out.append(delimiter);
        scopeElementCounts.push(0);
        return this;
    }

    private TsonWriter close(char delimiter) {
        int count = scopeElementCounts.pop();
        if (count > 0) {
            out.append(' ');
        }
        out.append(delimiter);
        return this;
    }

    /**
     * Inserts the separator before an element of the *currently open* scope -- a no-op at the top
     * level (no enclosing scope yet), otherwise always a single space, whether this is the first
     * element or a later one (the opening delimiter provides no space of its own).
     */
    private void beforeElement() {
        if (!scopeElementCounts.isEmpty()) {
            out.append(' ');
            scopeElementCounts.push(scopeElementCounts.pop() + 1);
        }
    }

    @Override
    public String toString() {
        return out.toString();
    }
}
