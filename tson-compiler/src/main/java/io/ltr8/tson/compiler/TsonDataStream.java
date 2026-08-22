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
import io.ltr8.tson.compiler.stream.TsonEventSource;
import io.ltr8.tson.compiler.stream.TypeRef;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
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
 * exception is {@code {}} disambiguation (§2.8): a record's first field name and a map's first
 * key share one opening delimiter, and the grammar only tells them apart by what follows the
 * first thing inside -- {@code :} for a record, {@code =>} for a map. The naive way to resolve
 * that is to fully parse whatever comes first (which can itself be an arbitrarily deep nested
 * value) and only then check which delimiter follows -- correct, but it would force this class to
 * buffer arbitrarily deep before emitting anything, undermining the point of being lazy. It's
 * avoidable because record-field position requires the first thing after <code>&#123;</code> to reduce to a
 * single bare token (no annotations, no type-ref, no nested container) -- anything else can only
 * ever be valid as a map key. That collapses the lookahead to at most two tokens, decided the
 * instant <code>&#123;</code> is seen, with no recursive buffering at all:
 *
 * <ul>
 *   <li>{@code {}} immediately followed by {@code @}, {@code !}, <code>&#123;</code>, {@code [}, or {@code
 *       _} can only be a map -- none of those can reduce to a bare field-name token, so a single
 *       token of lookahead settles it. A document that's actually malformed here (e.g. an
 *       annotated key immediately followed by {@code :} instead of {@code =>}) still commits to
 *       {@link MapStart} at this point; the mismatch surfaces one token later instead, at the
 *       point {@code =>} is expected -- a {@link TsonParseException} either way, just anchored to
 *       a slightly later token than a full first-value-then-decide parse would report.
 *   <li>{@code {}} followed by a bare token needs exactly one more token of lookahead: if {@code
 *       :} comes next it's a record field name; if {@code =>} comes next it's a map key that
 *       happens to be an unannotated, untyped token. Nothing else is legal there.
 * </ul>
 *
 * <p>This lookahead is bounded (at most two tokens, held in {@link #current}/{@link #pending})
 * regardless of document size or nesting depth -- it is never proportional to the size of the
 * first key/value's own subtree, unlike the naive "parse first, then decide" approach.
 *
 * <h2>Shared with Tier 3</h2>
 *
 * <p>{@link TsonDataParser} builds a full {@code Document} by pulling this class's public {@link
 * #hasNext()}/{@link #next()} and reducing the flat event sequence back into a tree -- it holds no
 * competing implementation of this grammar. Package-private beneath that: {@link
 * #nextDataValueEvents()}/{@link #nextCoreValueEvents()}/{@link #nextAnnotationEvents()} run this
 * same frame machinery for exactly one data-value/core-value/annotation at the current cursor
 * position, without the document-header/root wrapper {@link #ensureStarted()} imposes, and the
 * cursor primitives ({@link #peekToken()}, {@link #advance()}, {@link #check(TokenType)}, {@link
 * #expect(TokenType, String)}, ...) are reachable directly. Both exist so {@link TsonSchemaParser}
 * -- which extends {@link TsonDataParser} to reuse this exact machinery for [TSON-SCHEMA]'s own
 * shared productions, per §12.1 -- can interleave its own schema-only token handling with calls
 * back into the shared data grammar on the very same cursor, the same relationship it had with
 * {@link TsonDataParser}'s token list before this class existed.
 */
public final class TsonDataStream implements TsonEventSource {

    private final Lexer lexer;

    /** The next not-yet-consumed token -- lazily populated by {@link #peekToken()} on first use. */
    private int nesting;

    private Token current;

    /** A second lookahead token, buffered only transiently to resolve {@code {}} disambiguation. */
    private Token pending;

    /** End position of the most recently consumed token, exposed via {@link #lastTokenEnd()} -- three raw coordinates, not a stored {@link Position}, so a separator check (the hottest consumer, once per record field/map entry/array element) never allocates one; {@link #lastTokenEnd()} itself still materializes a real {@link Position} on demand for its own (much rarer) callers. */
    private int lastEndLine;
    private int lastEndColumn;
    private int lastEndByteOffset;

    private final Deque<Frame> frames = new ArrayDeque<>();
    private final Deque<TsonEvent> ready = new ArrayDeque<>();

    private boolean started;

    public TsonDataStream(String source) {
        this(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Reads directly off {@code source}'s own bytes (UTF-8), one lexer token at a time -- for a large
     * file this streams genuinely, never slurping the whole document into a {@code String} first the
     * way the {@code String} constructor's callers necessarily have. {@code source} is not closed here;
     * a caller that opened it owns closing it.
     */
    public TsonDataStream(InputStream source) {
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

    @Override
    public TsonEvent peek() {
        if (!hasNext()) {
            throw new NoSuchElementException("no more TSON stream events");
        }
        return ready.peek();
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

    /**
     * Runs the frame stack to completion starting from {@code start}, collecting every event it
     * (and anything it pushes) produces. Used for {@link #nextDataValueEvents()}/{@link
     * #nextCoreValueEvents()}/{@link #nextAnnotationEvents()}: each parses exactly one bounded
     * production at the current cursor position, standalone, without the document-header/root
     * wrapper {@link #ensureStarted()} imposes -- {@code frames}/{@code ready} are guaranteed
     * empty again when this returns, so repeated calls (interleaved with direct cursor use) are
     * always safe.
     */
    private List<TsonEvent> drain(Frame start) {
        List<TsonEvent> collected = new ArrayList<>();
        pushFrame(start);
        while (!frames.isEmpty()) {
            frames.pop().step();
            while (!ready.isEmpty()) {
                collected.add(ready.poll());
            }
        }
        return collected;
    }

    /** One full {@code data-value} (§2.3) at the current cursor position. */
    List<TsonEvent> nextDataValueEvents() {
        return drain(new DataValueFrame());
    }

    /** One bare {@code core-value} (§2.3) at the current cursor position -- see {@link CoreValueFrame}. */
    List<TsonEvent> nextCoreValueEvents() {
        return drain(new CoreValueFrame());
    }

    /** One standalone annotation (§3.1) at the current cursor position. */
    List<TsonEvent> nextAnnotationEvents() {
        return drain(new AnnotationOnlyFrame());
    }

    // ── Startup: header directives (§2.2), mirroring TsonDataParser.parseDocument ───────

    private void ensureStarted() {
        if (started) {
            return;
        }
        started = true;

        Position docStart = peekToken().start();
        Optional<String> id = Optional.empty();
        if (check(TokenType.DIRECTIVE) && "id".equals(peekDirectiveName())) {
            id = Optional.of(parseNamedDirective("id"));
        }

        if (check(TokenType.DIRECTIVE) && "meta".equals(peekDirectiveName())) {
            Position metaStart = peekToken().start();
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
    // Package-private (not private): TsonDataParser forwards these to TsonSchemaParser, which
    // needs raw token-level access for its own, non-data-grammar productions.

    /** Runs {@link Lexer#nextToken()} and immediately snapshots its text/position accessors into a retainable {@link Token} -- {@code current}/{@code pending} genuinely need to be addressable and compared pairwise across frame steps, unlike {@link Lexer}'s own single-token live cursor. */
    private static Token snapshot(Lexer lexer, TokenType type) {
        return new Token(type, lexer.text(), lexer.startLine(), lexer.startColumn(), lexer.startByteOffset(),
                lexer.endLine(), lexer.endColumn(), lexer.endByteOffset());
    }

    /**
     * Bracket-pair depth at the cursor, counted as tokens are consumed. <b>Only {@link TsonSchemaParser}'s
     * error recovery reads it</b>, to tell the record body it failed inside from the schema map it must get
     * back out to -- a failure at {@code first => { x: }} leaves the cursor on the <em>record's</em> closing
     * brace, and resynchronising on brace text alone would mistake it for the map's own. Counted here rather
     * than in the parser because this is the one place every token is consumed. {@code <}/{@code >} are not
     * counted: they are schema-only, and a stray one is skipped harmlessly where a miscount would not be.
     */
    int nesting() {
        return nesting;
    }

    Token peekToken() {
        if (current == null) {
            current = snapshot(lexer, lexer.nextToken());
        }
        return current;
    }

    /**
     * The token after {@link #peekToken()}, without consuming either -- the second of the at-most-two tokens of
     * lookahead this stream keeps. Package-private so {@link TsonSchemaParser}'s error recovery can recognise a
     * declaration start ({@code name =>}) while resynchronising, the one place two tokens decide the answer.
     */
    Token peekSecondToken() {
        return peekSecond();
    }

    private Token peekSecond() {
        if (pending == null) {
            pending = snapshot(lexer, lexer.nextToken());
        }
        return pending;
    }

    Token advance() {
        Token t = peekToken();
        lastEndLine = t.endLine();
        lastEndColumn = t.endColumn();
        lastEndByteOffset = t.endByteOffset();
        switch (t.type()) {
            case LBRACE, LBRACKET, LPAREN -> nesting++;
            case RBRACE, RBRACKET, RPAREN -> nesting = Math.max(0, nesting - 1);
            default -> { }
        }
        if (pending != null) {
            current = pending;
            pending = null;
        } else {
            current = snapshot(lexer, lexer.nextToken());
        }
        return t;
    }

    boolean check(TokenType type) {
        return peekToken().type() == type;
    }

    /**
     * Consumes a token of {@code type}, or fails naming {@code construct} -- <b>the construct the position
     * admits, phrased as the author would say it</b> ({@code "a record field's ':'"}), never the token class
     * that would have satisfied it. The token class is parser vocabulary: an author reading {@code expected
     * COLON (record field)} has to know what this parser calls things before the sentence tells them
     * anything, where {@code expected a record field's ':'} names the fix outright.
     */
    Token expect(TokenType type, String construct) {
        if (!check(type)) {
            throw mismatch(construct);
        }
        return advance();
    }

    /** The failure {@link #expect} raises, for a throw site that decides on more than one token's type. */
    TsonParseException mismatch(String construct) {
        return new TsonParseException("expected " + construct + ", found " + describe(peekToken()),
                construct, describe(peekToken()), peekToken().start());
    }

    /** A parse failure that states a rule rather than a substitution, so it carries no {@code expected}/{@code actual} pair. */
    TsonParseException parseError(String message) {
        return new TsonParseException(message, peekToken().start());
    }

    /**
     * End position of the most recently consumed token. {@link TsonSchemaParser} needs this
     * directly at two points (a removal clause's {@code -}, a {@code ?} adjacency check) where it
     * must confirm no whitespace separates the current token from the one just consumed.
     */
    Position lastTokenEnd() {
        return new Position(lastEndLine, lastEndColumn, lastEndByteOffset);
    }

    /**
     * One written token as an author would point at it. A quoted token names its form, since its text
     * alone ({@code abc}) is indistinguishable from an unquoted one and the difference is often the whole
     * problem; everything else is its own text in quotes. <b>The {@link TokenType} is deliberately not
     * printed</b> -- {@code '!' (BANG)} spends its second half restating the first in parser vocabulary.
     */
    static String describe(Token t) {
        return switch (t.type()) {
            case EOF -> "end of input";
            case SINGLE_LINE_STRING, MULTI_LINE_STRING -> "the quoted token '" + t.text() + "'";
            default -> "'" + t.text() + "'";
        };
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
     * Between elements of a record/map/array (§2.4): a separator (whitespace, a comma, or both)
     * is required unless the closing delimiter is immediately next; a trailing separator right
     * before the closing delimiter is likewise a parse error.
     */
    boolean consumeSeparatorOrCloseCheck(TokenType closing) {
        if (check(closing)) {
            return false;
        }
        boolean sawSeparator = !peekToken().startsAt(lastEndLine, lastEndColumn, lastEndByteOffset);
        if (check(TokenType.COMMA)) {
            advance();
            sawSeparator = true;
        }
        if (!sawSeparator) {
            throw parseError("adjacent values must be separated by whitespace, a comma, or both");
        }
        if (check(closing)) {
            throw parseError("a trailing separator is not permitted before " + describe(peekToken()));
        }
        return true;
    }

    /** Looks ahead at an upcoming {@code !!name} directive's name without consuming anything. */
    String peekDirectiveName() {
        Token name = peekSecond();
        return name.type() == TokenType.UNQUOTED ? name.text() : null;
    }

    /** {@code "!!" name ":" single-line-token}, requiring the directive name to equal {@code expectedName} (§3.3). */
    String parseNamedDirective(String expectedName) {
        Token bangbang = expect(TokenType.DIRECTIVE, "a directive");
        Token name = peekToken();
        if (name.type() != TokenType.UNQUOTED) {
            throw parseError("expected a directive name after '!!', found " + describe(name));
        }
        if (!bangbang.adjacentTo(name)) {
            throw parseError("'!!' must be immediately adjacent to the directive name (no whitespace)");
        }
        if (!name.text().equals(expectedName)) {
            throw parseError("directive '!!" + name.text() + "' is not permitted here (expected '!!"
                    + expectedName + "')");
        }
        advance();

        Token colon = peekToken();
        if (colon.type() != TokenType.COLON || !name.adjacentTo(colon)) {
            throw parseError("expected ':' immediately after directive name '!!" + expectedName + "'");
        }
        advance();

        Token arg = peekToken();
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
        Token name = peekToken();
        if (name.type() != TokenType.UNQUOTED) {
            if (name.type() == TokenType.LBRACKET && bang.adjacentTo(name)) {
                throw parseError("'![...]' writes an array type, which is schema syntax and not available in a "
                        + "data value (§3.2); write the array itself, or name the type in the schema "
                        + "('my_type => [...]') and write '!my_type' here");
            }
            throw parseError("expected a type name after '!', found " + describe(name));
        }
        if (!bang.adjacentTo(name)) {
            throw parseError("'!' must be immediately adjacent to the type name (no whitespace)");
        }
        advance();

        Token next = peekToken();
        // §3.2's three type-expression forms, each named for what the author wrote rather than left to the
        // separation rule below -- which would answer '!paged<order>' with "expected whitespace before '<'",
        // advice whose own result is a second error and which never states the rule that stops them.
        if (next.type() == TokenType.LESS_THAN) {
            throw parseError("'!" + name.text() + "<...>' applies type arguments, which are schema syntax and "
                    + "not available in a data value (§3.2): a data type-ref is a bare name. Name the "
                    + "application in the schema ('my_type => " + name.text() + "<...>') and write '!my_type' here");
        }
        if (next.type() == TokenType.QUESTION && name.adjacentTo(next)) {
            throw parseError("'!" + name.text() + "?' uses the optional suffix, which is schema syntax and not "
                    + "available in a data value (§3.2): optionality is a field's state where the schema "
                    + "declares it, and a value that is absent is written '_' (§2.9)");
        }
        if (!isStructuralDelimiter(next.type()) && name.adjacentTo(next)) {
            throw parseError("expected whitespace after type name '" + name.text()
                    + "' before " + describe(next));
        }
        return name.text();
    }

    /**
     * {@code field-name = token} (§7.4): any of the three token forms. Shared with [TSON-SCHEMA]'s identical
     * production (§12.1). {@code construct} names the position in the author's voice, exactly as
     * {@link #expect}'s does.
     */
    Token expectFieldNameToken(String construct) {
        Token name = peekToken();
        if (!isBareTokenType(name.type())) {
            throw mismatch(construct);
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
                throw parseError("unexpected content after the document's value: " + describe(peekToken()));
            }
            ready.add(new DocumentEnd(peekToken().start()));
        }
    }

    /**
     * Parses one full {@code data-value} at the current position: zero or more annotations, an
     * optional type-ref, then a core-value (§2.3, §7.4), delegating each part to {@link
     * AnnotationOnlyFrame}/{@link CoreValueFrame}. Stateless -- every step either finishes the
     * value outright or (for one annotation at a time) re-pushes a fresh instance of itself to
     * continue at the same logical position once the annotation's own value/close is done.
     */
    private final class DataValueFrame extends Frame {
        @Override
        void step() {
            Token t = peekToken();
            if (t.type() == TokenType.AT) {
                pushFrame(new DataValueFrame()); // continue this position once the annotation closes
                pushFrame(new AnnotationOnlyFrame());
                return;
            }
            if (t.type() == TokenType.BANG) {
                String name = parseTypeRefName();
                ready.add(new TypeRef(name, t.start()));
                pushFrame(new CoreValueFrame());
                return;
            }
            pushFrame(new CoreValueFrame());
        }
    }

    /**
     * One standalone annotation (§3.1): {@code "@" unquoted-token [ ":" data-value ]}. Split out
     * of {@link DataValueFrame} so {@link #nextAnnotationEvents()} can drive exactly this
     * production on its own -- [TSON-SCHEMA]'s own annotation positions (§12.1) reuse it the same
     * way {@link TsonSchemaParser} reuses {@code TsonDataParser.parseAnnotation()} today.
     */
    private final class AnnotationOnlyFrame extends Frame {
        @Override
        void step() {
            Token at = advance();
            Token name = peekToken();
            if (name.type() != TokenType.UNQUOTED) {
                throw parseError("expected an annotation name after '@', found " + describe(name));
            }
            if (!at.adjacentTo(name)) {
                throw parseError("'@' must be immediately adjacent to the annotation name (no whitespace)");
            }
            advance();

            if (check(TokenType.COLON) && name.adjacentTo(peekToken())) {
                advance(); // ':'
                ready.add(new AnnotationStart(name.text(), at.start()));
                pushFrame(new AnnotationEndFrame());
                pushFrame(new DataValueFrame()); // the annotation's own value
                return;
            }

            // Valueless: at least one whitespace character MUST follow the annotation name (§3.1).
            if (name.adjacentTo(peekToken())) {
                throw parseError("expected whitespace after annotation name '" + name.text()
                        + "' (or an adjacent ':' to give it a value)");
            }
            ready.add(new AnnotationStart(name.text(), at.start()));
            ready.add(new AnnotationEnd(peekToken().start()));
        }
    }

    /**
     * A bare {@code core-value} (§2.3, §7.4) -- no annotation/type-ref layer. Split out of {@link
     * DataValueFrame} so {@link #nextCoreValueEvents()} can drive exactly this narrower
     * production directly, for [TSON-SCHEMA] §5.5's {@code instance} ({@code "!" type-name ws
     * core-value}, corrected from the spec's own literal {@code data-value} -- see {@code
     * SPEC-FEEDBACK.md}), which must not accept a payload with its own competing annotations/type-ref.
     */
    private final class CoreValueFrame extends Frame {
        @Override
        void step() {
            Token t = peekToken();
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
            Token t1 = peekToken();

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
            ready.add(new AnnotationEnd(peekToken().start()));
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
                Position pos = peekToken().start();
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
            Token name = expectFieldNameToken("a record field name");
            expect(TokenType.COLON, "a record field's ':'");
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
                    Token arrow = expect(TokenType.MAP_ARROW, "a map entry's '=>'");
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
