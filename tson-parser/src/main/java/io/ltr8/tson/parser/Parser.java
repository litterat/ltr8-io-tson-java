package io.ltr8.tson.parser;

import io.ltr8.tson.parser.ast.AbsentValue;
import io.ltr8.tson.parser.ast.Annotation;
import io.ltr8.tson.parser.ast.ArrayValue;
import io.ltr8.tson.parser.ast.CoreValue;
import io.ltr8.tson.parser.ast.DataValue;
import io.ltr8.tson.parser.ast.Document;
import io.ltr8.tson.parser.ast.EmptyBrace;
import io.ltr8.tson.parser.ast.MapValue;
import io.ltr8.tson.parser.ast.RecordValue;
import io.ltr8.tson.parser.ast.ScopedValue;
import io.ltr8.tson.parser.ast.TokenForm;
import io.ltr8.tson.parser.ast.TokenValue;
import io.ltr8.tson.parser.lexer.Lexer;
import io.ltr8.tson.parser.lexer.Position;
import io.ltr8.tson.parser.lexer.Token;
import io.ltr8.tson.parser.lexer.TokenType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Parses a token stream into a {@link Document} per the data grammar of §2, §3, and §7.4.
 *
 * <p>Whitespace is already invisible by the time tokens reach this class -- the lexer discards
 * it and only leaves position gaps as evidence it was there. Two consequences follow throughout
 * this file: (1) wherever the grammar shows {@code ws} between tokens, no explicit handling is
 * needed here, it's already permitted by default; (2) wherever the spec requires tokens to be
 * strictly <em>adjacent</em> (no whitespace) -- {@code !}, {@code !!}, {@code @} to their
 * operand, {@code :} to a preceding annotation/directive name -- this class checks it explicitly
 * via {@link Position} equality (§7.5). The inverse also comes up once: valueless annotations
 * require a whitespace <em>gap</em> to follow (§3.1), checked as positions being unequal.
 *
 * <p>Rejects schema documents (header containing {@code !!meta}) with {@link SchemaDocumentException}
 * rather than attempting to parse them -- this is a Class 1 (data-format-only) processor (§1.5).
 */
public final class Parser {

    private final List<Token> tokens;
    private int pos;

    public Parser(String source) {
        this.tokens = new Lexer(source).tokenize();
        this.pos = 0;
    }

    public Document parseDocument() {
        Optional<String> id = Optional.empty();
        if (check(TokenType.DIRECTIVE) && "id".equals(peekDirectiveName())) {
            id = Optional.of(parseNamedDirective("id"));
        }

        if (check(TokenType.DIRECTIVE) && "meta".equals(peekDirectiveName())) {
            throw new SchemaDocumentException(peek().start());
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

        DataValue root = parseDataValue();

        if (!check(TokenType.EOF)) {
            throw parseError("unexpected content after the document's value: " + describe(peek()));
        }

        return new Document(id, schema, root);
    }

    // ── Values (§2.3, §7.4) ─────────────────────────────────────────────

    private ScopedValue parseScopedValue() {
        Optional<String> schemaRef = Optional.empty();
        if (check(TokenType.DIRECTIVE)) {
            String name = peekDirectiveName();
            if (!"schema".equals(name)) {
                throw parseError("directive '!!" + name + "' is not permitted here (only '!!schema' is)");
            }
            schemaRef = Optional.of(parseNamedDirective("schema"));
        }
        return new ScopedValue(schemaRef, parseDataValue());
    }

    private DataValue parseDataValue() {
        List<Annotation> annotations = new ArrayList<>();
        while (check(TokenType.AT)) {
            annotations.add(parseAnnotation());
        }
        Optional<String> typeRef = Optional.empty();
        if (check(TokenType.BANG)) {
            typeRef = Optional.of(parseTypeRef());
        }
        CoreValue core = parseCoreValue();
        return new DataValue(annotations, typeRef, core);
    }

    private CoreValue parseCoreValue() {
        Token t = peek();
        return switch (t.type()) {
            case LBRACE -> parseBraceValue();
            case LBRACKET -> parseArray();
            case ABSENT -> {
                advance();
                yield new AbsentValue();
            }
            case UNQUOTED -> {
                advance();
                yield new TokenValue(t.text(), TokenForm.UNQUOTED);
            }
            case SINGLE_LINE_STRING -> {
                advance();
                yield new TokenValue(t.text(), TokenForm.SINGLE_LINE_QUOTED);
            }
            case MULTI_LINE_STRING -> {
                advance();
                yield new TokenValue(t.text(), TokenForm.MULTI_LINE_QUOTED);
            }
            default -> throw parseError("expected a value (record, map, array, empty braces, "
                    + "the absent sentinel '_', or a token), found " + describe(t));
        };
    }

    // ── Augmentation (§3) ────────────────────────────────────────────────

    private Annotation parseAnnotation() {
        Token at = expect(TokenType.AT, "annotation");
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
            return new Annotation(name.text(), Optional.of(parseDataValue()));
        }

        // Valueless: at least one whitespace character MUST follow the annotation name (§3.1).
        if (name.end().equals(peek().start())) {
            throw parseError("expected whitespace after annotation name '" + name.text()
                    + "' (or an adjacent ':' to give it a value)");
        }
        return new Annotation(name.text(), Optional.empty());
    }

    private String parseTypeRef() {
        Token bang = expect(TokenType.BANG, "type annotation");
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

    /** {@code "!!" name ":" single-line-token}, requiring the directive name to equal {@code expectedName}. */
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
        return arg.text();
    }

    /** Looks ahead at an upcoming {@code !!name} directive's name without consuming anything. */
    private String peekDirectiveName() {
        Token name = peek(1);
        return name.type() == TokenType.UNQUOTED ? name.text() : null;
    }

    // ── Records and maps: brace disambiguation (§2.8) ───────────────────

    private CoreValue parseBraceValue() {
        expect(TokenType.LBRACE, "value");

        if (check(TokenType.RBRACE)) {
            advance();
            return new EmptyBrace();
        }

        DataValue first = parseDataValue();

        if (check(TokenType.COLON)) {
            if (!first.annotations().isEmpty() || first.typeRef().isPresent()
                    || !(first.coreValue() instanceof TokenValue tv)) {
                throw parseError("a record field name must be a bare token with no annotations "
                        + "and no type annotation");
            }
            advance(); // ':'
            List<RecordValue.Field> fields = new ArrayList<>();
            fields.add(new RecordValue.Field(tv.text(), parseScopedValue()));
            while (consumeSeparatorOrCloseCheck(TokenType.RBRACE)) {
                fields.add(parseField());
            }
            expect(TokenType.RBRACE, "record");
            return new RecordValue(fields);
        }

        if (check(TokenType.MAP_ARROW)) {
            advance();
            List<MapValue.MapEntry> entries = new ArrayList<>();
            entries.add(new MapValue.MapEntry(first, parseScopedValue()));
            while (consumeSeparatorOrCloseCheck(TokenType.RBRACE)) {
                entries.add(parseMapEntry());
            }
            expect(TokenType.RBRACE, "map");
            return new MapValue(entries);
        }

        throw parseError("a value inside curly braces must be followed by ':' (record) or "
                + "'=>' (map), found " + describe(peek()));
    }

    private RecordValue.Field parseField() {
        Token name = peek();
        if (name.type() != TokenType.UNQUOTED && name.type() != TokenType.SINGLE_LINE_STRING
                && name.type() != TokenType.MULTI_LINE_STRING) {
            throw parseError("expected a field name (a token), found " + describe(name));
        }
        advance();
        expect(TokenType.COLON, "record field");
        return new RecordValue.Field(name.text(), parseScopedValue());
    }

    private MapValue.MapEntry parseMapEntry() {
        DataValue key = parseDataValue();
        expect(TokenType.MAP_ARROW, "map entry");
        return new MapValue.MapEntry(key, parseScopedValue());
    }

    private CoreValue parseArray() {
        expect(TokenType.LBRACKET, "array");
        List<ScopedValue> elements = new ArrayList<>();
        if (!check(TokenType.RBRACKET)) {
            elements.add(parseScopedValue());
            while (consumeSeparatorOrCloseCheck(TokenType.RBRACKET)) {
                elements.add(parseScopedValue());
            }
        }
        expect(TokenType.RBRACKET, "array");
        return new ArrayValue(elements);
    }

    /**
     * Between elements of a record/map/array (§2.4): if the closing delimiter is next, no
     * separator is required (structural delimiters create their own token boundary) and this
     * returns {@code false}. Otherwise a separator -- whitespace, a comma, or both -- MUST be
     * present ("zero-width separation is a parse error"), and a separator immediately followed
     * by the closing delimiter is a trailing separator, also a parse error. Both are detected via
     * {@link Position} gaps, since whitespace itself leaves no token.
     */
    private boolean consumeSeparatorOrCloseCheck(TokenType closing) {
        if (check(closing)) {
            return false;
        }
        Position afterPrevious = tokens.get(pos - 1).end();
        boolean sawSeparator = !afterPrevious.equals(peek().start());
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

    private static boolean isStructuralDelimiter(TokenType type) {
        return switch (type) {
            case LBRACE, RBRACE, LBRACKET, RBRACKET, COLON, COMMA -> true;
            default -> false;
        };
    }

    // ── Cursor primitives ────────────────────────────────────────────────

    private Token peek() {
        return peek(0);
    }

    private Token peek(int offset) {
        int idx = Math.min(pos + offset, tokens.size() - 1);
        return tokens.get(idx);
    }

    private Token advance() {
        Token t = peek();
        if (pos < tokens.size() - 1) {
            pos++;
        }
        return t;
    }

    private boolean check(TokenType type) {
        return peek().type() == type;
    }

    private Token expect(TokenType type, String context) {
        if (!check(type)) {
            throw parseError("expected " + type + " (" + context + "), found " + describe(peek()));
        }
        return advance();
    }

    private ParseException parseError(String message) {
        return new ParseException(message, peek().start());
    }

    private static String describe(Token t) {
        if (t.type() == TokenType.EOF) {
            return "end of input";
        }
        return "'" + t.text() + "' (" + t.type() + ")";
    }
}
