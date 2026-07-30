package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.atom.AtomParseException;
import io.ltr8.tson.compiler.atom.UriParser;
import io.ltr8.tson.compiler.lexer.Lexer;
import io.ltr8.tson.compiler.lexer.Token;
import io.ltr8.tson.compiler.lexer.TokenType;
import io.ltr8.tson.compiler.stream.AbsentEvent;
import io.ltr8.tson.compiler.stream.AnnotationEnd;
import io.ltr8.tson.compiler.stream.AnnotationStart;
import io.ltr8.tson.compiler.stream.ArrayEnd;
import io.ltr8.tson.compiler.stream.ArrayStart;
import io.ltr8.tson.compiler.stream.DocumentEnd;
import io.ltr8.tson.compiler.stream.DocumentStart;
import io.ltr8.tson.compiler.stream.EmptyBraceEvent;
import io.ltr8.tson.compiler.stream.FieldName;
import io.ltr8.tson.compiler.stream.MapArrow;
import io.ltr8.tson.compiler.stream.MapEnd;
import io.ltr8.tson.compiler.stream.MapStart;
import io.ltr8.tson.compiler.stream.RecordEnd;
import io.ltr8.tson.compiler.stream.RecordStart;
import io.ltr8.tson.compiler.stream.SchemaRef;
import io.ltr8.tson.compiler.stream.TokenEvent;
import io.ltr8.tson.compiler.stream.TsonEvent;
import io.ltr8.tson.compiler.stream.TypeRef;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Tier 2: decomposes TSON source text into a lazy, pull-based {@link TsonEvent} stream (§2, §3,
 * §7.4) -- the same data grammar {@link TsonDataParser} (Tier 3) parses into a full {@code
 * Document} AST, but exposed one event at a time via {@link #next()} rather than materialized
 * into a tree. Built for large documents: memory held at any point is proportional to how many
 * containers are currently open (record/map/array nesting depth), never to the document's
 * overall size -- a sibling field in a million-field record costs nothing extra once its
 * predecessor's events have been consumed.
 *
 * <p>Driven directly off {@link Lexer#nextToken()}, one token at a time -- never {@link
 * Lexer#tokenize()} -- so the token stream itself is never materialized either. Recursion is
 * replaced with an explicit {@link Frame} stack (mirroring the call stack a recursive-descent
 * parser would use) precisely so a real Java call stack, and the thread-per-parse or continuation
 * machinery a hand-rolled coroutine would need to fake laziness, are both avoided -- the whole
 * point of this tier is to be cheap to run over a large file.
 *
 * <h2>The {@code {}} record/map lookahead</h2>
 *
 * <p>Every other decision in the grammar is resolved by the current token alone. The one
 * exception is {@code {}} disambiguation (§2.8): {@link TsonDataParser} resolves it by parsing
 * the brace's first data-value in full and then checking whether {@code :} or {@code =>} follows.
 * That's exactly right for a full first-value-then-decide strategy, but a naive equivalent here
 * would need to buffer arbitrarily deep before it could emit anything -- undermining the point of
 * being lazy. It's avoidable because record-field position requires the first thing after {@code
 * {} to reduce to a single bare token (no annotations, no type-ref, no nested container) --
 * anything else can only ever be valid as a map key. That collapses the lookahead to at most two
 * tokens, decided the instant {@code {} is seen, with no recursive buffering at all:
 *
 * <ul>
 *   <li>{@code {}} immediately followed by {@code @}, {@code !}, {@code {}, {@code [}, or {@code
 *       _} can only be a map -- none of those can reduce to a bare field-name token, so a single
 *       token of lookahead settles it. ({@link TsonDataParser}'s own disambiguation would still
 *       report a parse error if the document is actually malformed here (e.g. an annotated key
 *       immediately followed by {@code :} instead of {@code =>}) -- this class commits to {@link
 *       MapStart} regardless and lets the mismatch surface a token later, at the point it expects
 *       {@code =>}; the diagnostic wording differs slightly from {@link TsonDataParser}'s in that
 *       one malformed-input corner, both are {@link TsonParseException}.)
 *   <li>{@code {}} followed by a bare token needs exactly one more token of lookahead: if {@code
 *       :} comes next it's a record field name; if {@code =>} comes next it's a map key that
 *       happens to be an unannotated, untyped token. Nothing else is legal there.
 * </ul>
 *
 * <p>This lookahead is bounded (at most two tokens, held in {@link #current}/{@link #pending})
 * regardless of document size or nesting depth -- it is never proportional to the size of the
 * first key/value's own subtree, unlike the naive "parse first, then decide" approach.
 */
public final class TsonDataStream implements Iterator<TsonEvent> {

    private final Lexer lexer;

    /** The next not-yet-consumed token -- always populated once {@link #ensureStarted()} has run. */
    private Token current;

    /** A second lookahead token, buffered only transiently to resolve {@code {}} disambiguation. */
    private Token pending;

    /** End position of the most recently consumed token; stands in for {@code TsonDataParser}'s {@code tokens.get(pos - 1).end()}. */
    private Position lastEnd;

    private final Deque<Frame> frames = new ArrayDeque<>();
    private final Deque<TsonEvent> ready = new ArrayDeque<>();

    private boolean started;

    public TsonDataStream(String source) {
        this.lexer = new Lexer(source);
    }

    @Override
    public boolean hasNext() {
        ensureStarted();
        fill();
        return !ready.isEmpty();
    }

    @Override
    public TsonEvent next() {
        if (!hasNext()) {
            throw new NoSuchElementException("no more TSON stream events");
        }
        return ready.poll();
    }

    /** Runs frames until an event is ready or the stream is exhausted. */
    private void fill() {
        while (ready.isEmpty() && !frames.isEmpty()) {
            frames.pop().step();
        }
    }

    private void pushFrame(Frame frame) {
        frames.push(frame);
    }

    // ── Startup: header directives (§2.2), mirroring TsonDataParser.parseDocument ───────

    private void ensureStarted() {
        if (started) {
            return;
        }
        started = true;
        current = lexer.nextToken();

        Position docStart = peek().start();
        Optional<String> id = Optional.empty();
        if (check(TokenType.DIRECTIVE) && "id".equals(peekDirectiveName())) {
            id = Optional.of(parseNamedDirective("id"));
        }

        if (check(TokenType.DIRECTIVE) && "meta".equals(peekDirectiveName())) {
            Position metaStart = peek().start();
            parseNamedDirective("meta");
            throw new TsonUnsupportedDocumentException(metaStart);
        }

        Optional<String> schema = Optional.empty();
        if (check(TokenType.DIRECTIVE)) {
            String name = peekDirectiveName();
            if ("schema".equals(name)) {
                schema = Optional.of(parseNamedDirective("schema"));
            } else {
                throw parseError("directive '!!" + name + "' is not permitted here "
                        + "(expected '!!schema' or the start of the document's value)");
            }
        }

        ready.add(new DocumentStart(id, schema, docStart));
        pushFrame(new RootFrame());
        pushFrame(new DataValueFrame());
    }

    // ── Cursor primitives over Lexer.nextToken() (bounded 2-token lookahead) ────────────

    private Token peek() {
        return current;
    }

    private Token peekSecond() {
        if (pending == null) {
            pending = lexer.nextToken();
        }
        return pending;
    }

    private Token advance() {
        Token t = current;
        lastEnd = t.end();
        if (pending != null) {
            current = pending;
            pending = null;
        } else {
            current = lexer.nextToken();
        }
        return t;
    }

    private boolean check(TokenType type) {
        return current.type() == type;
    }

    private Token expect(TokenType type, String context) {
        if (!check(type)) {
            throw parseError("expected " + type + " (" + context + "), found " + describe(peek()));
        }
        return advance();
    }

    private TsonParseException parseError(String message) {
        return new TsonParseException(message, peek().start());
    }

    private static String describe(Token t) {
        if (t.type() == TokenType.EOF) {
            return "end of input";
        }
        return "'" + t.text() + "' (" + t.type() + ")";
    }

    private static boolean isStructuralDelimiter(TokenType type) {
        return switch (type) {
            case LBRACE, RBRACE, LBRACKET, RBRACKET, COLON, COMMA -> true;
            default -> false;
        };
    }

    /** Every token type a {@code {} can be immediately followed by that can only ever be a map key (never a bare field name). */
    private static boolean isAlwaysMapStart(TokenType type) {
        return switch (type) {
            case AT, BANG, LBRACE, LBRACKET, ABSENT -> true;
            default -> false;
        };
    }

    private static boolean isBareTokenType(TokenType type) {
        return switch (type) {
            case UNQUOTED, SINGLE_LINE_STRING, MULTI_LINE_STRING -> true;
            default -> false;
        };
    }

    private static TokenForm formOf(TokenType type) {
        return switch (type) {
            case UNQUOTED -> TokenForm.UNQUOTED;
            case SINGLE_LINE_STRING -> TokenForm.SINGLE_LINE_QUOTED;
            case MULTI_LINE_STRING -> TokenForm.MULTI_LINE_QUOTED;
            default -> throw new IllegalStateException("not a bare-token type: " + type);
        };
    }

    /**
     * Mirrors {@code TsonDataParser.consumeSeparatorOrCloseCheck}: a separator (whitespace, a
     * comma, or both) is required between elements unless the closing delimiter is immediately
     * next; a trailing separator right before the closing delimiter is likewise a parse error.
     */
    private boolean consumeSeparatorOrCloseCheck(TokenType closing) {
        if (check(closing)) {
            return false;
        }
        boolean sawSeparator = !lastEnd.equals(peek().start());
        if (check(TokenType.COMMA)) {
            advance();
            sawSeparator = true;
        }
        if (!sawSeparator) {
            throw parseError("adjacent values must be separated by whitespace, a comma, or both");
        }
        if (check(closing)) {
            throw parseError("a trailing separator is not permitted before " + describe(peek()));
        }
        return true;
    }

    /** Looks ahead at an upcoming {@code !!name} directive's name without consuming anything. */
    private String peekDirectiveName() {
        Token name = peekSecond();
        return name.type() == TokenType.UNQUOTED ? name.text() : null;
    }

    /** {@code "!!" name ":" single-line-token}, requiring the directive name to equal {@code expectedName}. See {@code TsonDataParser.parseNamedDirective} for the identical rule set (§3.3). */
    private String parseNamedDirective(String expectedName) {
        Token bangbang = expect(TokenType.DIRECTIVE, "directive");
        Token name = peek();
        if (name.type() != TokenType.UNQUOTED) {
            throw parseError("expected a directive name after '!!', found " + describe(name));
        }
        if (!bangbang.end().equals(name.start())) {
            throw parseError("'!!' must be immediately adjacent to the directive name (no whitespace)");
        }
        if (!name.text().equals(expectedName)) {
            throw parseError("directive '!!" + name.text() + "' is not permitted here (expected '!!"
                    + expectedName + "')");
        }
        advance();

        Token colon = peek();
        if (colon.type() != TokenType.COLON || !name.end().equals(colon.start())) {
            throw parseError("expected ':' immediately after directive name '!!" + expectedName + "'");
        }
        advance();

        Token arg = peek();
        if (arg.type() == TokenType.MULTI_LINE_STRING) {
            throw parseError("a multi-line token is not permitted as a directive argument; "
                    + "use a single-line quoted token");
        }
        if (arg.type() != TokenType.SINGLE_LINE_STRING) {
            throw parseError("expected a single-line quoted token as the argument to '!!"
                    + expectedName + "', found " + describe(arg));
        }
        advance();

        try {
            UriParser.UNCONSTRAINED.read(new TokenValue(arg.text(), TokenForm.SINGLE_LINE_QUOTED));
        } catch (AtomParseException e) {
            throw new TsonParseException(
                    "'!!" + expectedName + "' argument '" + arg.text() + "' is not a valid URI (§3.3)", arg.start());
        }
        return arg.text();
    }

    private String parseTypeRefName() {
        Token bang = advance();
        Token name = peek();
        if (name.type() != TokenType.UNQUOTED) {
            throw parseError("expected a type name after '!', found " + describe(name));
        }
        if (!bang.end().equals(name.start())) {
            throw parseError("'!' must be immediately adjacent to the type name (no whitespace)");
        }
        advance();

        Token next = peek();
        if (!isStructuralDelimiter(next.type()) && name.end().equals(next.start())) {
            throw parseError("expected whitespace after type name '" + name.text()
                    + "' before " + describe(next));
        }
        return name.text();
    }

    private Token expectFieldNameToken() {
        Token name = peek();
        if (name.type() != TokenType.UNQUOTED && name.type() != TokenType.SINGLE_LINE_STRING
                && name.type() != TokenType.MULTI_LINE_STRING) {
            throw parseError("expected a field name (a token) for a record field, found " + describe(name));
        }
        advance();
        return name;
    }

    // ── The explicit frame stack replacing recursion ────────────────────────────────────

    /** One outstanding step of work, resumed in LIFO order -- the stack-based stand-in for a recursive call frame. */
    private abstract class Frame {
        abstract void step();
    }

    /** Below the document root's {@link DataValueFrame}: checks for trailing content and closes the stream. */
    private final class RootFrame extends Frame {
        @Override
        void step() {
            if (!check(TokenType.EOF)) {
                throw parseError("unexpected content after the document's value: " + describe(peek()));
            }
            ready.add(new DocumentEnd(peek().start()));
        }
    }

    /**
     * Parses one full {@code data-value} at the current position: zero or more annotations, an
     * optional type-ref, then a core-value (§2.3, §7.4). Stateless -- every step either finishes
     * the value outright or (for one annotation at a time) re-pushes a fresh instance of itself
     * to continue at the same logical position once the annotation's own value/close is done.
     */
    private final class DataValueFrame extends Frame {
        @Override
        void step() {
            Token t = peek();
            if (t.type() == TokenType.AT) {
                parseAnnotation();
                return;
            }
            if (t.type() == TokenType.BANG) {
                String name = parseTypeRefName();
                ready.add(new TypeRef(name, t.start()));
                parseCoreValue();
                return;
            }
            parseCoreValue();
        }

        private void parseAnnotation() {
            Token at = advance();
            Token name = peek();
            if (name.type() != TokenType.UNQUOTED) {
                throw parseError("expected an annotation name after '@', found " + describe(name));
            }
            if (!at.end().equals(name.start())) {
                throw parseError("'@' must be immediately adjacent to the annotation name (no whitespace)");
            }
            advance();

            if (check(TokenType.COLON) && name.end().equals(peek().start())) {
                advance(); // ':'
                ready.add(new AnnotationStart(name.text(), at.start()));
                pushFrame(new DataValueFrame()); // continue this position once the annotation closes
                pushFrame(new AnnotationEndFrame());
                pushFrame(new DataValueFrame()); // the annotation's own value
                return;
            }

            // Valueless: at least one whitespace character MUST follow the annotation name (§3.1).
            if (name.end().equals(peek().start())) {
                throw parseError("expected whitespace after annotation name '" + name.text()
                        + "' (or an adjacent ':' to give it a value)");
            }
            ready.add(new AnnotationStart(name.text(), at.start()));
            ready.add(new AnnotationEnd(peek().start()));
            pushFrame(new DataValueFrame()); // continue this position for further annotations/type-ref/core-value
        }

        private void parseCoreValue() {
            Token t = peek();
            switch (t.type()) {
                case LBRACE -> parseBraceValue();
                case LBRACKET -> {
                    advance();
                    ready.add(new ArrayStart(t.start()));
                    pushFrame(new ArrayFrame(true));
                }
                case ABSENT -> {
                    advance();
                    ready.add(new AbsentEvent(t.start()));
                }
                case UNQUOTED, SINGLE_LINE_STRING, MULTI_LINE_STRING -> {
                    advance();
                    ready.add(new TokenEvent(t.text(), formOf(t.type()), t.start()));
                }
                default -> throw parseError("expected a value (record, map, array, empty braces, "
                        + "the absent sentinel '_', or a token), found " + describe(t));
            }
        }

        /** The one place brace disambiguation happens -- see this class's own Javadoc. */
        private void parseBraceValue() {
            Token lbrace = advance();
            Token t1 = peek();

            if (t1.type() == TokenType.RBRACE) {
                advance();
                ready.add(new EmptyBraceEvent(lbrace.start()));
                return;
            }

            if (isAlwaysMapStart(t1.type())) {
                ready.add(new MapStart(lbrace.start()));
                pushFrame(new MapFrame(MapFrame.Mode.AWAITING_ARROW));
                pushFrame(new DataValueFrame()); // the (possibly annotated/typed/nested) first key
                return;
            }

            if (isBareTokenType(t1.type())) {
                Token t2 = peekSecond();
                if (t2.type() == TokenType.COLON) {
                    advance(); // field-name token
                    advance(); // ':'
                    ready.add(new RecordStart(lbrace.start()));
                    ready.add(new FieldName(t1.text(), t1.start()));
                    pushFrame(new RecordFrame());
                    pushFrame(new ScopedValueFrame());
                    return;
                }
                if (t2.type() == TokenType.MAP_ARROW) {
                    advance(); // key token
                    advance(); // '=>'
                    ready.add(new MapStart(lbrace.start()));
                    ready.add(new TokenEvent(t1.text(), formOf(t1.type()), t1.start()));
                    ready.add(new MapArrow(t2.start()));
                    pushFrame(new MapFrame(MapFrame.Mode.AFTER_ENTRY));
                    pushFrame(new ScopedValueFrame());
                    return;
                }
                throw parseError("a value inside curly braces must be followed by ':' (record) or "
                        + "'=>' (map), found " + describe(t2));
            }

            throw parseError("expected a value (record, map, array, empty braces, "
                    + "the absent sentinel '_', or a token), found " + describe(t1));
        }
    }

    private final class AnnotationEndFrame extends Frame {
        @Override
        void step() {
            ready.add(new AnnotationEnd(peek().start()));
        }
    }

    /** {@code [ schema-directive ws ] data-value} (§2.3): record field values, map entry values, array elements. */
    private final class ScopedValueFrame extends Frame {
        @Override
        void step() {
            if (check(TokenType.DIRECTIVE)) {
                String name = peekDirectiveName();
                if (!"schema".equals(name)) {
                    throw parseError("directive '!!" + name + "' is not permitted here (only '!!schema' is)");
                }
                Position pos = peek().start();
                String uri = parseNamedDirective("schema");
                ready.add(new SchemaRef(uri, pos));
            }
            pushFrame(new DataValueFrame());
        }
    }

    /** Repeatable step for every record field after the first (whose name/colon brace-disambiguation already consumed). */
    private final class RecordFrame extends Frame {
        @Override
        void step() {
            if (check(TokenType.RBRACE)) {
                Token rb = advance();
                ready.add(new RecordEnd(rb.start()));
                return;
            }
            consumeSeparatorOrCloseCheck(TokenType.RBRACE);
            Token name = expectFieldNameToken();
            expect(TokenType.COLON, "record field");
            ready.add(new FieldName(name.text(), name.start()));
            pushFrame(new RecordFrame());
            pushFrame(new ScopedValueFrame());
        }
    }

    /** Repeatable step for every map entry after the first (whose key/arrow brace-disambiguation already consumed or set up). */
    private final class MapFrame extends Frame {
        enum Mode { AFTER_ENTRY, AWAITING_ARROW }

        private final Mode mode;

        MapFrame(Mode mode) {
            this.mode = mode;
        }

        @Override
        void step() {
            switch (mode) {
                case AFTER_ENTRY -> {
                    if (check(TokenType.RBRACE)) {
                        Token rb = advance();
                        ready.add(new MapEnd(rb.start()));
                        return;
                    }
                    consumeSeparatorOrCloseCheck(TokenType.RBRACE);
                    pushFrame(new MapFrame(Mode.AWAITING_ARROW));
                    pushFrame(new DataValueFrame()); // the next key
                }
                case AWAITING_ARROW -> {
                    Token arrow = expect(TokenType.MAP_ARROW, "map entry");
                    ready.add(new MapArrow(arrow.start()));
                    pushFrame(new MapFrame(Mode.AFTER_ENTRY));
                    pushFrame(new ScopedValueFrame());
                }
            }
        }
    }

    /** Repeatable step for every array element; {@code first} skips the separator check the very first element never needs. */
    private final class ArrayFrame extends Frame {
        private final boolean first;

        ArrayFrame(boolean first) {
            this.first = first;
        }

        @Override
        void step() {
            if (check(TokenType.RBRACKET)) {
                Token rb = advance();
                ready.add(new ArrayEnd(rb.start()));
                return;
            }
            if (!first) {
                consumeSeparatorOrCloseCheck(TokenType.RBRACKET);
            }
            pushFrame(new ArrayFrame(false));
            pushFrame(new ScopedValueFrame());
        }
    }
}
