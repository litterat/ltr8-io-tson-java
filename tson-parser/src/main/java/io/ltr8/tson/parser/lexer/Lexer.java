package io.ltr8.tson.parser.lexer;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts TSON source text into a stream of {@link Token}s per spec §7.2–§7.3.
 *
 * <p>The lexer is a single hand-written scanner over the source {@code String},
 * addressed by Unicode code point (so supplementary-plane characters, which
 * are valid in identifiers per UAX #31, are never split). It performs escape
 * processing and multi-line whitespace stripping itself, since the spec
 * defines a token's "text" as content after those steps (§2.4) — form
 * (quoted vs. unquoted) is a lexical property the parser and resolver
 * consult, but the escape/whitespace mechanics are purely lexical.
 *
 * <p>Not thread-safe; a {@code Lexer} instance is single-use over one source
 * string. Errors are reported by throwing {@link LexException} immediately
 * (fail-fast) rather than the "SHOULD continue processing" best practice of
 * §8.1, which is left to a future error-recovery pass.
 */
public final class Lexer {

    private final String source;
    private int pos;        // char (UTF-16) index into source
    private int line;       // 1-based
    private int col;        // 1-based, counted in code points
    private int byteOffset; // 0-based UTF-8 byte offset

    public Lexer(String source) {
        this.source = stripBom(source);
        this.pos = 0;
        this.line = 1;
        this.col = 1;
        this.byteOffset = 0;
    }

    private static final char BOM = 0xFEFF;

    private static String stripBom(String s) {
        return (!s.isEmpty() && s.charAt(0) == BOM) ? s.substring(1) : s;
    }

    /** Lexes the entire source, returning all tokens including a trailing {@link TokenType#EOF}. */
    public List<Token> tokenize() {
        List<Token> tokens = new ArrayList<>();
        Token t;
        do {
            t = nextToken();
            tokens.add(t);
        } while (t.type() != TokenType.EOF);
        return tokens;
    }

    /** Scans and returns the next token, or an {@link TokenType#EOF} token if the input is exhausted. */
    public Token nextToken() {
        skipWhitespace();
        Position start = currentPosition();

        if (atEnd()) {
            return new Token(TokenType.EOF, "", start, start);
        }

        int cp = peekCodePoint();

        if (cp == '"') {
            return lexQuoted(start);
        }
        if (cp == '_') {
            advance();
            return finish(TokenType.ABSENT, "_", start);
        }
        if (cp == '{') {
            advance();
            return finish(TokenType.LBRACE, "{", start);
        }
        if (cp == '}') {
            advance();
            return finish(TokenType.RBRACE, "}", start);
        }
        if (cp == '[') {
            advance();
            return finish(TokenType.LBRACKET, "[", start);
        }
        if (cp == ']') {
            advance();
            return finish(TokenType.RBRACKET, "]", start);
        }
        if (cp == ':') {
            advance();
            return finish(TokenType.COLON, ":", start);
        }
        if (cp == ',') {
            advance();
            return finish(TokenType.COMMA, ",", start);
        }
        if (cp == '=') {
            return lexEqualsOrMapArrow(start);
        }
        if (cp == '!') {
            return lexBangOrDirective(start);
        }
        if (cp == '.') {
            return lexDotOrRangeOrUnquoted(start);
        }
        if (cp == '-' || cp == '+') {
            return lexSignOrUnquoted(start, cp);
        }
        if (isUnquotedStart(cp)) {
            return lexUnquoted(start);
        }

        TokenType special = specialTokenType(cp);
        if (special != null) {
            advance();
            return finish(special, new String(Character.toChars(cp)), start);
        }

        throw lexError("unrecognised character U+%04X".formatted(cp), start);
    }

    // ── Compound-token lookahead (§7.2.4) ──────────────────────────────

    private Token lexEqualsOrMapArrow(Position start) {
        advance(); // '='
        if (peekCodePoint() == '>') {
            advance();
            return finish(TokenType.MAP_ARROW, "=>", start);
        }
        return finish(TokenType.EQUAL, "=", start);
    }

    private Token lexBangOrDirective(Position start) {
        advance(); // '!'
        if (peekCodePoint() == '!') {
            advance();
            return finish(TokenType.DIRECTIVE, "!!", start);
        }
        return finish(TokenType.BANG, "!", start);
    }

    private Token lexDotOrRangeOrUnquoted(Position start) {
        advance(); // '.'
        int next = peekCodePoint();
        if (next == '.') {
            advance();
            return finish(TokenType.RANGE, "..", start);
        }
        if (next != -1 && isUnquotedContinuation(next)) {
            StringBuilder sb = new StringBuilder(".");
            scanUnquotedContinuation(sb);
            String text = sb.toString();
            checkNfc(text, start);
            return finish(TokenType.UNQUOTED, text, start);
        }
        throw lexError("unexpected character '.': a bare '.' has no grammar role; write \".\" (quoted) for a literal dot", start);
    }

    private Token lexSignOrUnquoted(Position start, int signCp) {
        advance(); // sign
        int next = peekCodePoint();
        if (next != -1 && isUnquotedContinuation(next)) {
            StringBuilder sb = new StringBuilder();
            sb.appendCodePoint(signCp);
            scanUnquotedContinuation(sb);
            String text = sb.toString();
            checkNfc(text, start);
            return finish(TokenType.UNQUOTED, text, start);
        }
        if (signCp == '-') {
            return finish(TokenType.MINUS, "-", start);
        }
        throw lexError("unexpected character '+': a bare '+' has no grammar role; write \"+\" (quoted) for a literal plus sign", start);
    }

    // ── Unquoted tokens (§7.1, §7.2.1) ─────────────────────────────────

    private Token lexUnquoted(Position start) {
        StringBuilder sb = new StringBuilder();
        sb.appendCodePoint(advance());
        scanUnquotedContinuation(sb);
        String text = sb.toString();
        checkNfc(text, start);
        return finish(TokenType.UNQUOTED, text, start);
    }

    /** Consumes unquoted-continuation characters, stopping before a {@code ..} run (§7.2 rule 3). */
    private void scanUnquotedContinuation(StringBuilder sb) {
        while (!atEnd()) {
            int cp = peekCodePoint();
            if (cp == '.') {
                int next = codePointAtOrEof(pos + Character.charCount(cp));
                if (next == '.') {
                    break;
                }
                sb.appendCodePoint(advance());
                continue;
            }
            if (isUnquotedContinuation(cp)) {
                sb.appendCodePoint(advance());
            } else {
                break;
            }
        }
    }

    private void checkNfc(String text, Position start) {
        if (!Normalizer.isNormalized(text, Normalizer.Form.NFC)) {
            throw lexError("unquoted token '" + text + "' is not NFC-normalized", start);
        }
    }

    private static boolean isUnquotedStart(int cp) {
        return Character.isUnicodeIdentifierStart(cp) || isDecimalDigit(cp);
    }

    private static boolean isUnquotedContinuation(int cp) {
        return Character.isUnicodeIdentifierPart(cp) || cp == '-' || cp == '+' || cp == '.';
    }

    private static boolean isDecimalDigit(int cp) {
        return Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER;
    }

    // ── Special tokens (§7.2.5) ─────────────────────────────────────────

    private static TokenType specialTokenType(int cp) {
        return switch (cp) {
            case '@' -> TokenType.AT;
            case '&' -> TokenType.AMPERSAND;
            case '<' -> TokenType.LESS_THAN;
            case '>' -> TokenType.GREATER_THAN;
            case '?' -> TokenType.QUESTION;
            case '~' -> TokenType.TILDE;
            case '|' -> TokenType.PIPE;
            case ';' -> TokenType.SEMICOLON;
            case '(' -> TokenType.LPAREN;
            case ')' -> TokenType.RPAREN;
            case '^' -> TokenType.CARET;
            default -> null;
        };
    }

    // ── Quoted tokens (§7.2.2, §7.2.3) ──────────────────────────────────

    private Token lexQuoted(Position start) {
        advance(); // opening '"'
        if (peekChar(0) == '"' && peekChar(1) == '"') {
            advance();
            advance();
            return lexMultilineToken(start);
        }
        return lexSingleLineToken(start);
    }

    private Token lexSingleLineToken(Position start) {
        StringBuilder raw = new StringBuilder();
        while (true) {
            if (atEnd()) {
                throw lexError("unterminated single-line token", start);
            }
            int cp = peekCodePoint();
            if (cp == '"') {
                advance();
                break;
            }
            if (cp == '\\') {
                raw.appendCodePoint(advance());
                if (atEnd()) {
                    throw lexError("unterminated escape sequence", currentPosition());
                }
                raw.appendCodePoint(advance());
                continue;
            }
            if (cp < 0x20) {
                throw lexError("control character U+%04X not permitted unescaped in a single-line token".formatted(cp), currentPosition());
            }
            if (cp == 0x0085 || cp == 0x2028 || cp == 0x2029) {
                throw lexError("line terminator U+%04X not permitted unescaped in a single-line token; use \\u%04X".formatted(cp, cp), currentPosition());
            }
            raw.appendCodePoint(advance());
        }
        return finish(TokenType.SINGLE_LINE_STRING, decodeAllEscapes(raw.toString(), start), start);
    }

    private Token lexMultilineToken(Position start) {
        // Already consumed the opening """.
        skipSpacesTabs();
        if (!atEnd() && !isLineTerminatorCp(peekCodePoint())) {
            throw lexError("content not permitted after the opening \"\"\" of a multi-line token", currentPosition());
        }
        if (!atEnd()) {
            consumeLineTerminator();
        }

        List<String> contentLines = new ArrayList<>();
        String closingIndent = null;

        while (true) {
            if (atEnd()) {
                throw lexError("unterminated multi-line token", start);
            }
            String rawLine = readRawLine();
            String indent = leadingWhitespace(rawLine);
            String afterIndent = rawLine.substring(indent.length());
            if (isClosingDelimiterContent(afterIndent)) {
                closingIndent = indent;
                if (!atEnd()) {
                    consumeLineTerminator();
                }
                break;
            }
            contentLines.add(rawLine);
            if (atEnd()) {
                throw lexError("unterminated multi-line token", start);
            }
            consumeLineTerminator();
        }

        String prefix = computeCommonPrefix(contentLines, closingIndent);

        StringBuilder decoded = new StringBuilder();
        for (int i = 0; i < contentLines.size(); i++) {
            String line = stripTrailing(removePrefix(contentLines.get(i), prefix));
            decoded.append(decodeAllEscapes(line, start));
            if (i < contentLines.size() - 1) {
                decoded.append('\n');
            }
        }
        return finish(TokenType.MULTI_LINE_STRING, decoded.toString(), start);
    }

    private static boolean isClosingDelimiterContent(String trimmed) {
        if (!trimmed.startsWith("\"\"\"")) {
            return false;
        }
        String rest = trimmed.substring(3);
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c != ' ' && c != '\t') {
                return false;
            }
        }
        return true;
    }

    /** Reads characters up to (not including) the next line terminator or EOF. */
    private String readRawLine() {
        StringBuilder sb = new StringBuilder();
        while (!atEnd() && !isLineTerminatorCp(peekCodePoint())) {
            sb.appendCodePoint(advance());
        }
        return sb.toString();
    }

    private static String computeCommonPrefix(List<String> contentLines, String closingIndent) {
        String common = closingIndent;
        for (String line : contentLines) {
            if (isBlank(line)) {
                continue;
            }
            common = commonCharPrefix(common, leadingWhitespace(line));
        }
        return common;
    }

    private static boolean isBlank(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c != ' ' && c != '\t') {
                return false;
            }
        }
        return true;
    }

    private static String leadingWhitespace(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return line.substring(0, i);
    }

    private static String stripTrailing(String line) {
        int end = line.length();
        while (end > 0 && (line.charAt(end - 1) == ' ' || line.charAt(end - 1) == '\t')) {
            end--;
        }
        return line.substring(0, end);
    }

    private static String commonCharPrefix(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return a.substring(0, i);
    }

    private static String removePrefix(String line, String prefix) {
        int i = 0;
        while (i < prefix.length() && i < line.length() && line.charAt(i) == prefix.charAt(i)) {
            i++;
        }
        return line.substring(i);
    }

    // ── Escape decoding, shared by single-line and multi-line tokens ───
    // (§7.2.2; multi-line applies this after whitespace stripping, §7.2.3 rule 5)

    private String decodeAllEscapes(String raw, Position errorPos) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == '\\') {
                i = decodeEscapeSequence(raw, i, sb, errorPos);
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /** Decodes one escape sequence starting at {@code text.charAt(i) == '\\'}. Returns the index after it. */
    private int decodeEscapeSequence(String text, int i, StringBuilder sb, Position errorPos) {
        int n = text.length();
        i++; // skip backslash
        if (i >= n) {
            throw lexError("unterminated escape sequence", errorPos);
        }
        char e = text.charAt(i);
        switch (e) {
            case '"':
                sb.append('"');
                return i + 1;
            case '\\':
                sb.append('\\');
                return i + 1;
            case '/':
                sb.append('/');
                return i + 1;
            case 'b':
                sb.append('\b');
                return i + 1;
            case 'f':
                sb.append('\f');
                return i + 1;
            case 'n':
                sb.append('\n');
                return i + 1;
            case 'r':
                sb.append('\r');
                return i + 1;
            case 't':
                sb.append('\t');
                return i + 1;
            case 's':
                sb.append(' ');
                return i + 1;
            case 'u':
                return decodeUnicodeEscape(text, i + 1, sb, errorPos);
            default:
                throw lexError("invalid escape sequence '\\" + e + "'", errorPos);
        }
    }

    private int decodeUnicodeEscape(String text, int idx, StringBuilder sb, Position errorPos) {
        int[] r1 = readHex4(text, idx, errorPos);
        int cu = r1[0];
        int next = r1[1];
        if (Character.isHighSurrogate((char) cu)) {
            if (next + 1 < text.length() && text.charAt(next) == '\\' && text.charAt(next + 1) == 'u') {
                int[] r2 = readHex4(text, next + 2, errorPos);
                int cu2 = r2[0];
                if (!Character.isLowSurrogate((char) cu2)) {
                    throw lexError("high surrogate escape not followed by a low surrogate escape", errorPos);
                }
                sb.append((char) cu).append((char) cu2);
                return r2[1];
            }
            throw lexError("high surrogate escape not followed by a low surrogate escape", errorPos);
        }
        if (Character.isLowSurrogate((char) cu)) {
            throw lexError("lone low surrogate escape", errorPos);
        }
        sb.append((char) cu);
        return next;
    }

    private int[] readHex4(String text, int idx, Position errorPos) {
        if (idx + 4 > text.length()) {
            throw lexError("incomplete unicode escape", errorPos);
        }
        int value = 0;
        for (int k = 0; k < 4; k++) {
            int digit = Character.digit(text.charAt(idx + k), 16);
            if (digit < 0) {
                throw lexError("invalid hex digit in unicode escape", errorPos);
            }
            value = (value << 4) | digit;
        }
        return new int[]{value, idx + 4};
    }

    // ── Whitespace (§7.1, §7.2 rule 1) ──────────────────────────────────

    private void skipWhitespace() {
        while (!atEnd() && isPatternWhiteSpace(peekCodePoint())) {
            advance();
        }
    }

    private void skipSpacesTabs() {
        while (!atEnd()) {
            int cp = peekCodePoint();
            if (cp == ' ' || cp == '\t') {
                advance();
            } else {
                break;
            }
        }
    }

    private static boolean isPatternWhiteSpace(int cp) {
        return switch (cp) {
            case 0x0009, 0x000A, 0x000B, 0x000C, 0x000D, 0x0020, 0x0085, 0x200E, 0x200F, 0x2028, 0x2029 -> true;
            default -> false;
        };
    }

    private static boolean isLineTerminatorCp(int cp) {
        return cp == '\n' || cp == '\r' || cp == 0x0085 || cp == 0x2028 || cp == 0x2029;
    }

    private void consumeLineTerminator() {
        int cp = peekCodePoint();
        if (cp == '\r') {
            advance();
            if (!atEnd() && peekCodePoint() == '\n') {
                advance();
            }
        } else {
            advance();
        }
    }

    // ── Cursor primitives ────────────────────────────────────────────────

    private boolean atEnd() {
        return pos >= source.length();
    }

    private int peekCodePoint() {
        return codePointAtOrEof(pos);
    }

    private int codePointAtOrEof(int charIndex) {
        return charIndex < source.length() ? source.codePointAt(charIndex) : -1;
    }

    /** Looks ahead {@code delta} UTF-16 chars from the cursor without consuming; -1 past the end. */
    private int peekChar(int delta) {
        int idx = pos + delta;
        return idx < source.length() ? source.charAt(idx) : -1;
    }

    /** Consumes and returns the code point at the cursor, advancing position and line/column tracking. */
    private int advance() {
        int cp = source.codePointAt(pos);
        int charCount = Character.charCount(cp);
        pos += charCount;
        byteOffset += utf8Length(cp);

        if (cp == '\n' || cp == 0x0085 || cp == 0x2028 || cp == 0x2029) {
            line++;
            col = 1;
        } else if (cp == '\r') {
            if (pos < source.length() && source.charAt(pos) == '\n') {
                // Defer the line bump to the paired LF's own advance() call.
            } else {
                line++;
                col = 1;
            }
        } else {
            col++;
        }
        return cp;
    }

    private static int utf8Length(int cp) {
        if (cp <= 0x7F) {
            return 1;
        } else if (cp <= 0x7FF) {
            return 2;
        } else if (cp <= 0xFFFF) {
            return 3;
        } else {
            return 4;
        }
    }

    private Position currentPosition() {
        return new Position(line, col, byteOffset);
    }

    private Token finish(TokenType type, String text, Position start) {
        return new Token(type, text, start, currentPosition());
    }

    private LexException lexError(String message, Position position) {
        return new LexException(message, position);
    }
}
