package io.ltr8.tson.json.stream;

import io.ltr8.tson.base.LimitExceededException;
import io.ltr8.tson.base.LimitsPolicy;
import io.ltr8.tson.base.ParseException;
import io.ltr8.tson.json.JsonPosition;
import io.ltr8.tson.json.lexer.JsonLexer;
import io.ltr8.tson.json.lexer.JsonTokenType;

import java.io.InputStream;
import java.util.NoSuchElementException;

/**
 * RFC 8259's structural grammar over a {@link JsonLexer}, as a lazy pull-based {@link JsonEventSource}.
 *
 * <p>The only thing in this module that walks source text: it reduces tokens into {@link JsonEvent}s
 * and holds nothing but a frame stack, so memory held at any point is proportional to nesting depth
 * rather than to document size. The layers above -- the tree, the binding reader, and the
 * schema-directed decode of [TSON-JSON] §5-§8 -- consume events and hold no grammar of their own.
 *
 * <p><b>No token lookahead at all.</b> JSON's grammar is LL(1) on already-lexed tokens and never needs
 * a pushback: after <code>{</code> the next token is either <code>}</code> or a member name and
 * consuming it decides which; after a value it is either a separator or a closer. That is a real
 * difference from {@code TsonDataStream}, which spends two tokens of lookahead on the
 * record-versus-map brace idiom -- an ambiguity JSON does not have because it does not draw the
 * distinction at all. The one event of lookahead {@link #peek()} offers is a produced event held back,
 * not a token.
 *
 * <p><b>Grammar only, per §3.1's layering.</b> Three things this deliberately does not do:
 *
 * <ul>
 * <li><b>It does not dedupe member names.</b> §3.1 makes a repeat an error whose <em>category</em>
 *     follows the position's type -- a resolver error at record positions, a validation error where
 *     only the declared key type relates the pair -- which is a fact no grammar layer holds. The tree
 *     applies the rule where JEP 540 does, and the schema-directed decode applies it with a category.
 * <li><b>It does not interpret a value.</b> A number is its source lexeme (§5.3), a string is its
 *     decoded content, and {@code null} is a JSON value rather than the absent sentinel -- §7 turns it
 *     into one, at a typed position, which this layer has none of.
 * <li><b>It reserves no member name.</b> §3.2's {@code $}-namespace is reserved where an object is read
 *     as a record or an annotation object, which is a question about the position's type.
 * </ul>
 *
 * <p><b>Nesting depth is bounded here</b> ([TSON-JSON] §10.1), because this is the one place every
 * container is opened -- so a refusal lands before any consumer descends, which matters because every
 * consumer of this stream recurses where the stream itself iterates. The bound arrives as an {@code
 * int} rather than as a policy object because a stream needs one number, not a policy -- but the number
 * and the refusal are the processor's, not this encoding's: §10.1 makes the bound [TSON-DATA] §9.1's
 * policy "in JSON clothing, and the same policy applies with the same defaults", so this counts against
 * {@link LimitsPolicy}'s own default and refuses with {@link LimitExceededException}, the same
 * type the text encoding refuses with. A second default or a second exception would be a second answer to
 * one question.
 *
 * <p>Not thread-safe; single-use over one source.
 */
public final class JsonStream implements JsonEventSource {

    private final JsonLexer lexer;
    private final int maxDepth;

    /**
     * One entry per open container, deepest last: {@code true} for an object, {@code false} for an
     * array. That single bit is the whole of a frame -- JSON containers carry no other state, where a
     * TSON frame has to remember whether a brace resolved to a record or a map and how far its own
     * lookahead reached. Grown on demand rather than sized from {@link #maxDepth}, which a caller may
     * legitimately set far above any depth the document reaches.
     */
    private boolean[] frames = new boolean[16];
    private int depth;

    private State state = State.ROOT_VALUE;

    /** The event {@link #peek()} produced and {@link #next()} has not yet handed out. */
    private JsonEvent lookahead;

    /** Where the grammar is, between events. */
    private enum State {
        /** The document's one value is due. */
        ROOT_VALUE,
        /** Just after <code>{</code>: a member name or <code>}</code>. */
        OBJECT_FIRST_MEMBER,
        /** Just after a member separator: a member name, and no <code>}</code> -- JSON has no trailing comma. */
        OBJECT_MEMBER,
        /** A member's value is due, its name and {@code :} consumed. */
        OBJECT_VALUE,
        /** A member's value is complete: {@code ,} or <code>}</code>. */
        OBJECT_SEPARATOR,
        /** Just after {@code [}: a value or {@code ]}. */
        ARRAY_FIRST_ELEMENT,
        /** Just after an element separator: a value, and no {@code ]}. */
        ARRAY_ELEMENT,
        /** An element is complete: {@code ,} or {@code ]}. */
        ARRAY_SEPARATOR,
        /** The root value is complete; only end of input may follow. */
        DOCUMENT_END,
        /** {@link JsonEvent.EndOfDocument} has been produced. */
        DONE
    }

    public JsonStream(InputStream source) {
        this(new JsonLexer(source), LimitsPolicy.DEFAULT_MAX_DEPTH);
    }

    public JsonStream(String source) {
        this(new JsonLexer(source), LimitsPolicy.DEFAULT_MAX_DEPTH);
    }

    public JsonStream(InputStream source, int maxDepth) {
        this(new JsonLexer(source), maxDepth);
    }

    public JsonStream(String source, int maxDepth) {
        this(new JsonLexer(source), maxDepth);
    }

    private JsonStream(JsonLexer lexer, int maxDepth) {
        if (maxDepth < 1) {
            throw new IllegalArgumentException("maxDepth must be at least 1, not " + maxDepth);
        }
        this.lexer = lexer;
        this.maxDepth = maxDepth;
    }

    // ── The event source ─────────────────────────────────────────────────

    @Override
    public boolean hasNext() {
        return lookahead != null || state != State.DONE;
    }

    @Override
    public JsonEvent peek() {
        if (lookahead == null) {
            lookahead = advance();
        }
        return lookahead;
    }

    @Override
    public JsonEvent next() {
        if (lookahead != null) {
            JsonEvent held = lookahead;
            lookahead = null;
            return held;
        }
        return advance();
    }

    /** The next event, produced by consuming as many tokens as the grammar needs -- one, at every step. */
    private JsonEvent advance() {
        return switch (state) {
            case ROOT_VALUE, OBJECT_VALUE, ARRAY_FIRST_ELEMENT, ARRAY_ELEMENT -> value();
            case OBJECT_FIRST_MEMBER, OBJECT_MEMBER -> member();
            case OBJECT_SEPARATOR -> objectSeparator();
            case ARRAY_SEPARATOR -> arraySeparator();
            case DOCUMENT_END -> endOfDocument();
            case DONE -> throw new NoSuchElementException("the document's events are exhausted");
        };
    }

    // ── Values ───────────────────────────────────────────────────────────

    /**
     * One value, at whichever position is due -- and, for {@code [} and <code>{</code>, only its opening
     * event: the container's contents are the states that follow.
     *
     * <p>{@link State#ARRAY_FIRST_ELEMENT} is the one value position that may instead be a closer, an
     * empty array being the only container whose emptiness is decided where a value would stand.
     */
    private JsonEvent value() {
        JsonTokenType token = lexer.nextToken();
        JsonPosition at = lexer.start();

        if (token == JsonTokenType.END_ARRAY && state == State.ARRAY_FIRST_ELEMENT) {
            return closeArray(at);
        }

        return switch (token) {
            case BEGIN_OBJECT -> {
                push(true, at);
                state = State.OBJECT_FIRST_MEMBER;
                yield new JsonEvent.ObjectStart(at);
            }
            case BEGIN_ARRAY -> {
                push(false, at);
                state = State.ARRAY_FIRST_ELEMENT;
                yield new JsonEvent.ArrayStart(at);
            }
            case STRING -> completed(new JsonEvent.StringValue(lexer.text(), at));
            case NUMBER -> completed(new JsonEvent.NumberValue(lexer.text(), at));
            case TRUE -> completed(new JsonEvent.BooleanValue(true, at));
            case FALSE -> completed(new JsonEvent.BooleanValue(false, at));
            case NULL -> completed(new JsonEvent.NullValue(at));
            default -> throw unexpected(token, at, switch (state) {
                case ROOT_VALUE -> "a JSON document is one value";
                case OBJECT_VALUE -> "a member's value is due";
                case ARRAY_FIRST_ELEMENT -> "an element or ']' is due";
                default -> "an element is due";
            });
        };
    }

    /** A member name and the {@code :} after it, leaving the member's own value due. */
    private JsonEvent member() {
        JsonTokenType token = lexer.nextToken();
        JsonPosition at = lexer.start();

        if (token == JsonTokenType.END_OBJECT && state == State.OBJECT_FIRST_MEMBER) {
            return closeObject(at);
        }
        if (token != JsonTokenType.STRING) {
            // The two cases worth telling apart: a `}` after a separator is the trailing comma RFC 8259
            // does not admit, and everything else is a member name that is not a quoted string.
            throw unexpected(token, at, token == JsonTokenType.END_OBJECT
                    ? "a member name is due -- JSON admits no trailing comma"
                    : "a member name is due, and a member name is a quoted string");
        }

        JsonEvent.MemberName name = new JsonEvent.MemberName(lexer.text(), at);
        JsonTokenType separator = lexer.nextToken();
        if (separator != JsonTokenType.NAME_SEPARATOR) {
            throw unexpected(separator, lexer.start(), "':' separates a member name from its value");
        }
        state = State.OBJECT_VALUE;
        return name;
    }

    // ── Separators and closers ───────────────────────────────────────────

    private JsonEvent objectSeparator() {
        JsonTokenType token = lexer.nextToken();
        JsonPosition at = lexer.start();
        return switch (token) {
            case VALUE_SEPARATOR -> {
                state = State.OBJECT_MEMBER;
                yield advance();
            }
            case END_OBJECT -> closeObject(at);
            default -> throw unexpected(token, at, "',' or '}' follows a member's value");
        };
    }

    private JsonEvent arraySeparator() {
        JsonTokenType token = lexer.nextToken();
        JsonPosition at = lexer.start();
        return switch (token) {
            case VALUE_SEPARATOR -> {
                state = State.ARRAY_ELEMENT;
                yield advance();
            }
            case END_ARRAY -> closeArray(at);
            default -> throw unexpected(token, at, "',' or ']' follows an element");
        };
    }

    private JsonEvent closeObject(JsonPosition at) {
        pop();
        return completed(new JsonEvent.ObjectEnd(at));
    }

    private JsonEvent closeArray(JsonPosition at) {
        pop();
        return completed(new JsonEvent.ArrayEnd(at));
    }

    /**
     * Marks the value just produced complete and moves to whatever follows one at this depth: a
     * separator inside a container, or the end of the document at the root.
     */
    private JsonEvent completed(JsonEvent event) {
        state = depth == 0
                ? State.DOCUMENT_END
                : inObject() ? State.OBJECT_SEPARATOR : State.ARRAY_SEPARATOR;
        return event;
    }

    /**
     * The pull past the root value, which is what rejects trailing content -- {@code "[1] junk"} is
     * refused here and nowhere else.
     */
    private JsonEvent endOfDocument() {
        JsonTokenType token = lexer.nextToken();
        if (token != JsonTokenType.EOF) {
            throw unexpected(token, lexer.start(), "a JSON document is one value, and this one is complete");
        }
        state = State.DONE;
        return new JsonEvent.EndOfDocument(lexer.start());
    }

    // ── The frame stack, and §10.1's depth bound ─────────────────────────

    private void push(boolean object, JsonPosition at) {
        if (depth == maxDepth) {
            throw new LimitExceededException(
                    "the document nests deeper than this processor reads (%d)".formatted(maxDepth), maxDepth, at);
        }
        if (depth == frames.length) {
            boolean[] grown = new boolean[frames.length * 2];
            System.arraycopy(frames, 0, grown, 0, frames.length);
            frames = grown;
        }
        frames[depth++] = object;
    }

    private void pop() {
        depth--;
    }

    /** Whether the innermost open container is an object. Never called at depth zero. */
    private boolean inObject() {
        return frames[depth - 1];
    }

    // ── Errors ───────────────────────────────────────────────────────────

    /**
     * Names the <b>construct the position admits</b> rather than the token class that was found --
     * "a member name is due" tells an author what to write where "expected STRING" tells them what a
     * lexer calls the thing they did write. {@code TsonSchemaParser} makes the same choice.
     */
    private static ParseException unexpected(JsonTokenType found, JsonPosition at, String expected) {
        return new ParseException("%s, and %s is not one".formatted(expected, describe(found)), at);
    }

    private static String describe(JsonTokenType type) {
        return switch (type) {
            case BEGIN_OBJECT -> "'{'";
            case END_OBJECT -> "'}'";
            case BEGIN_ARRAY -> "'['";
            case END_ARRAY -> "']'";
            case NAME_SEPARATOR -> "':'";
            case VALUE_SEPARATOR -> "','";
            case STRING -> "a string";
            case NUMBER -> "a number";
            case TRUE -> "'true'";
            case FALSE -> "'false'";
            case NULL -> "'null'";
            case EOF -> "the end of the document";
        };
    }
}
