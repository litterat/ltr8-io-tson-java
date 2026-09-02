package io.ltr8.tson.compiler.lexer;

import io.ltr8.tson.compiler.Position;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts TSON source bytes into a stream of {@link Token}s per spec §7.2–§7.3.
 *
 * <p>The lexer is a single hand-written scanner over UTF-8 bytes read incrementally from an
 * {@link InputStream}, decoding UTF-8 itself (§9.1) and addressed one Unicode code point
 * at a time (so supplementary-plane characters, which are valid in identifiers per UAX #31, are
 * never split). At most a couple of code points of lookahead beyond the cursor are ever buffered
 * ({@link #lookaheadCodePoints}) — every lexical rule in this class needs to peek at most one or two code
 * points ahead of the current position (`""` disambiguation, the `..` range-vs-continuation check,
 * `\r\n` pairing), never further back or further forward — so memory held at any point is bounded
 * regardless of source size, the same "never materialize the whole input" principle
 * {@code TsonDataStream}/Tier 2 apply one layer up. It performs escape processing and multi-line
 * whitespace stripping itself, since the spec defines a token's "text" as content after those steps
 * (§2.4) — form (quoted vs. unquoted) is a lexical property the compiler and resolver consult, but
 * the escape/whitespace mechanics are purely lexical.
 *
 * <p><b>{@link #nextToken()} returns only the {@link TokenType}, not a {@link Token}.</b> The
 * token's own text and position are read off separately, via {@link #text()}/{@link
 * #startLine()}/{@link #startColumn()}/{@link #startByteOffset()}/{@link #endLine()}/{@link
 * #endColumn()}/{@link #endByteOffset()} — accessors reflecting whichever token {@link
 * #nextToken()} most recently produced, valid until the next call. This avoids allocating two
 * {@link Position} objects (start and end) for every single token lexed, whether or not a caller
 * ever needs to retain one — {@code endLine()}/{@code endColumn()}/{@code endByteOffset()} are
 * simply the live cursor's own coordinates the instant a token finishes (nothing else advances the
 * cursor between a token finishing and the next {@link #nextToken()} call), and {@code startLine()}/
 * etc. are a small snapshot of the same three coordinates taken once, at the top of {@link
 * #nextToken()}, before that token's own characters are consumed. A caller wanting an addressable,
 * retainable {@link Token} snapshot (rather than reading the live-cursor accessors immediately)
 * builds one itself from these seven values, exactly as {@link #tokenize()} does.
 *
 * <p>Not thread-safe; a {@code Lexer} instance is single-use over one source stream. Errors are
 * reported by throwing {@link LexException} immediately (fail-fast) rather than the "SHOULD
 * continue processing" best practice of §8.1, which is left to a future error-recovery pass.
 */
public final class Lexer {

    private final InputStream source;

    /**
     * Bytes pulled off {@link #source} in bulk, decoded one code point at a time by {@link
     * #decodeCodePoint()}.
     *
     * <p><b>The bulk read is the point, not the buffer's size.</b> Reading a byte (or a character) at a
     * time from the stream -- which is what a code-point-addressed lexer naturally wants to do -- costs a
     * call and, through a {@code Reader}, an allocation per character. Reading a block at a
     * time makes that per-block, and the block is deliberately modest: it is throughput, not a lookahead
     * window ({@link #lookaheadCodePoints} is that, and stays two code points deep), so a large document
     * gains nothing from a larger one and a small document should not pay for it.
     * {@code AllocationHarnessTest} pins the result.
     */
    private final byte[] bytes = new byte[512];
    private int bytePosition;
    private int byteLimit;
    private boolean sourceExhausted;

    /**
     * Bytes decoded so far -- {@link #byteOffset}'s bytes plus the lookahead's, on the same base (a leading
     * BOM counts toward neither, §7.1). The difference across one {@link #decodeCodePoint()} is that code
     * point's own byte length, which is how the offset is counted rather than derived.
     */
    private int bytesDecoded;

    /**
     * Code points decoded but not yet consumed by {@link #advance()}, with the byte length each was decoded
     * from -- never more than two, the most any lexical rule here looks ahead.
     *
     * <p>The lengths are carried rather than recomputed from the code point, which is what makes {@link
     * #byteOffset} <b>counted, not derived</b>: §8.1 requires a byte offset in every error report, and a
     * length re-derived from the decoded value is only right while the input is well-formed UTF-8 -- the
     * exact case where an offset matters most is the one where it would be wrong.
     */
    private final int[] lookaheadCodePoints = new int[4];
    private final int[] lookaheadByteLengths = new int[4];
    private int lookaheadCount;

    /**
     * The last code point {@link #advance()} consumed, or -1 before the first -- the character on the near side
     * of a whitespace run, which is half of what {@link #skipWhitespace} needs to decide whether an ignorable
     * format control sits at a token boundary or inside a token.
     */
    private int lastCodePoint = -1;

    private int line;       // 1-based; also the most recently lexed token's own *end* line
    private int col;        // 1-based, counted in code points; also that token's own end column
    private int byteOffset; // 0-based UTF-8 byte offset; also that token's own end byte offset

    // The most recently lexed (or in-progress) token's own start coordinates and text -- a
    // snapshot taken once per nextToken() call, exposed via the accessors below.
    private int tokenStartLine;
    private int tokenStartColumn;
    private int tokenStartByteOffset;
    private String tokenText;

    public Lexer(InputStream source) {
        this.source = source;
        this.line = 1;
        this.col = 1;
        this.byteOffset = 0;
        stripLeadingBom();
    }

    private static final int BOM = 0xFEFF;

    /**
     * A single leading BOM is discarded invisibly -- not counted toward {@link #line}/{@link #col}/{@link
     * #byteOffset}, matching §7.1. Accepting one is an encoding courtesy on §7.1's own authority and not a
     * debt to any other format: editors still emit it, and a document that opens with one is otherwise
     * well-formed UTF-8. A BOM anywhere else outside a quoted token is "an unrecognised character
     * and a lexer error": between tokens it reaches the dispatch's own fallthrough, and <em>inside</em> an
     * unquoted token {@link #isProfileContinue} refuses it, which is not free -- the JDK identifier predicate
     * admits it, and did until issue #229.
     */
    private void stripLeadingBom() {
        if (peekCodePointAt(0) == BOM) {
            bytesDecoded -= lookaheadByteLengths[0];   // §7.1: not a character at offset zero -- not there at all
            dropBufferedCodePoint();
        }
    }

    /** The most recently lexed token's own text (§2.4) -- see this class's own Javadoc for exactly when this is valid. */
    public String text() {
        return tokenText;
    }

    /** The most recently lexed token's own start line (1-based). */
    public int startLine() {
        return tokenStartLine;
    }

    /** The most recently lexed token's own start column (1-based, code points). */
    public int startColumn() {
        return tokenStartColumn;
    }

    /** The most recently lexed token's own start byte offset (0-based, UTF-8). */
    public int startByteOffset() {
        return tokenStartByteOffset;
    }

    /** The most recently lexed token's own end line (1-based) -- the live cursor's own current line. */
    public int endLine() {
        return line;
    }

    /** The most recently lexed token's own end column (1-based, code points) -- the live cursor's own current column. */
    public int endColumn() {
        return col;
    }

    /** The most recently lexed token's own end byte offset (0-based, UTF-8) -- the live cursor's own current byte offset. */
    public int endByteOffset() {
        return byteOffset;
    }

    /** Lexes the entire source, returning all tokens including a trailing {@link TokenType#EOF}. */
    public List<Token> tokenize() {
        List<Token> tokens = new ArrayList<>();
        TokenType type;
        do {
            type = nextToken();
            tokens.add(new Token(type, tokenText, tokenStartLine, tokenStartColumn, tokenStartByteOffset,
                    line, col, byteOffset));
        } while (type != TokenType.EOF);
        return tokens;
    }

    /** Scans the next token, returning its {@link TokenType} -- see this class's own Javadoc for reading its text/position. */
    public TokenType nextToken() {
        skipWhitespace();
        tokenStartLine = line;
        tokenStartColumn = col;
        tokenStartByteOffset = byteOffset;

        if (atEnd()) {
            return finish(TokenType.EOF, "");
        }

        int cp = peekCodePoint();

        if (cp == '"') {
            return lexQuoted();
        }
        if (cp == '_') {
            advance();
            return finish(TokenType.ABSENT, "_");
        }
        if (cp == '{') {
            advance();
            return finish(TokenType.LBRACE, "{");
        }
        if (cp == '}') {
            advance();
            return finish(TokenType.RBRACE, "}");
        }
        if (cp == '[') {
            advance();
            return finish(TokenType.LBRACKET, "[");
        }
        if (cp == ']') {
            advance();
            return finish(TokenType.RBRACKET, "]");
        }
        if (cp == ':') {
            advance();
            return finish(TokenType.COLON, ":");
        }
        if (cp == ',') {
            advance();
            return finish(TokenType.COMMA, ",");
        }
        if (cp == '=') {
            return lexEqualsOrMapArrow();
        }
        if (cp == '!') {
            return lexBangOrDirective();
        }
        if (cp == '.') {
            return lexDotOrRangeOrUnquoted();
        }
        if (cp == '-' || cp == '+') {
            return lexSignOrUnquoted(cp);
        }
        if (isUnquotedStart(cp)) {
            return lexUnquoted();
        }

        TokenType special = specialTokenType(cp);
        if (special != null) {
            advance();
            return finish(special, specialTokenText(cp));
        }

        throw errorAtTokenStart("unrecognised character U+%04X".formatted(cp));
    }

    // ── Compound-token lookahead (§7.2.4) ──────────────────────────────

    private TokenType lexEqualsOrMapArrow() {
        advance(); // '='
        if (peekCodePoint() == '>') {
            advance();
            return finish(TokenType.MAP_ARROW, "=>");
        }
        return finish(TokenType.EQUAL, "=");
    }

    private TokenType lexBangOrDirective() {
        advance(); // '!'
        if (peekCodePoint() == '!') {
            advance();
            return finish(TokenType.DIRECTIVE, "!!");
        }
        return finish(TokenType.BANG, "!");
    }

    private TokenType lexDotOrRangeOrUnquoted() {
        advance(); // '.'
        int next = peekCodePoint();
        if (next == '.') {
            advance();
            return finish(TokenType.RANGE, "..");
        }
        if (next != -1 && isUnquotedContinuation(next)) {
            StringBuilder sb = new StringBuilder(".");
            scanUnquotedContinuation(sb);
            String text = sb.toString();
            checkNfc(text);
            return finish(TokenType.UNQUOTED, text);
        }
        throw errorAtTokenStart("unexpected character '.': a bare '.' has no grammar role; write \".\" (quoted) for a literal dot");
    }

    private TokenType lexSignOrUnquoted(int signCp) {
        advance(); // sign
        int next = peekCodePoint();
        if (next != -1 && isUnquotedContinuation(next)) {
            StringBuilder sb = new StringBuilder();
            sb.appendCodePoint(signCp);
            scanUnquotedContinuation(sb);
            String text = sb.toString();
            checkNfc(text);
            return finish(TokenType.UNQUOTED, text);
        }
        if (signCp == '-') {
            return finish(TokenType.MINUS, "-");
        }
        throw errorAtTokenStart("unexpected character '+': a bare '+' has no grammar role; write \"+\" (quoted) for a literal plus sign");
    }

    // ── Unquoted tokens (§7.1, §7.2.1) ─────────────────────────────────

    private TokenType lexUnquoted() {
        StringBuilder sb = new StringBuilder();
        sb.appendCodePoint(advance());
        scanUnquotedContinuation(sb);
        String text = sb.toString();
        checkNfc(text);
        return finish(TokenType.UNQUOTED, text);
    }

    /** Consumes unquoted-continuation characters, stopping before a {@code ..} run (§7.2 rule 3). */
    private void scanUnquotedContinuation(StringBuilder sb) {
        while (!atEnd()) {
            int cp = peekCodePoint();
            if (cp == '.') {
                int next = peekCodePointAt(1);
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

    private void checkNfc(String text) {
        if (!Normalizer.isNormalized(text, Normalizer.Form.NFC)) {
            throw errorAtTokenStart("unquoted token '" + text + "' is not NFC-normalized");
        }
    }

    /** §7.1's {@code Start = XID_Start ∪ Nd ∪ { - + . }} -- the three extension characters are dispatched before this is reached ("Profile boundaries"). */
    private static boolean isUnquotedStart(int cp) {
        return Xid.isStart(cp) || isDecimalDigit(cp);
    }

    /**
     * §7.1's {@code Continue = XID_Continue ∪ { - + . }}, as the property states it -- <b>including ZWNJ and
     * ZWJ</b>, which {@code XID_Continue} contains and which §7.1 admits, relocating their safety rule to
     * the name layer.
     *
     * <p><b>The token layer follows the property; the name layer applies the contextual rule.</b> A joiner is
     * a token character, so an unquoted <em>value</em> may contain one and its content is its own. Whether a
     * joiner may appear in a <em>name</em> is a different question, and {@code IdentifierParser} answers it
     * with UTS #39 §3.1.1.1 (§7.7 rule 2) -- permitted where it has a shaping effect, refused where it is
     * invisible. The split is what makes a blanket exclusion unnecessary: one would forbid Persian
     * {@code کتاب<ZWNJ>ها} and still let {@code "ad<ZWNJ>min"} reach a name through a quoted spelling, which
     * is outside this production entirely.
     */
    private static boolean isUnquotedContinuation(int cp) {
        return Xid.isContinue(cp) || cp == '-' || cp == '+' || cp == '.';
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

    /** {@code cp}'s own text, as a compile-time string literal (interned by the JVM) rather than {@code new String(Character.toChars(cp))} -- every one of these eleven characters has exactly one possible spelling. */
    private static String specialTokenText(int cp) {
        return switch (cp) {
            case '@' -> "@";
            case '&' -> "&";
            case '<' -> "<";
            case '>' -> ">";
            case '?' -> "?";
            case '~' -> "~";
            case '|' -> "|";
            case ';' -> ";";
            case '(' -> "(";
            case ')' -> ")";
            case '^' -> "^";
            default -> throw new IllegalStateException("unreachable: U+%04X is not a special token".formatted(cp));
        };
    }

    // ── Quoted tokens (§7.2.2, §7.2.3) ──────────────────────────────────

    private TokenType lexQuoted() {
        advance(); // opening '"'
        if (peekCodePointAt(0) == '"' && peekCodePointAt(1) == '"') {
            advance();
            advance();
            return lexMultilineToken();
        }
        return lexSingleLineToken();
    }

    private TokenType lexSingleLineToken() {
        StringBuilder raw = new StringBuilder();
        boolean escaped = false;
        while (true) {
            if (atEnd()) {
                throw errorAtTokenStart("unterminated single-line token");
            }
            int cp = peekCodePoint();
            if (cp == '"') {
                advance();
                break;
            }
            if (cp == '\\') {
                escaped = true;
                raw.appendCodePoint(advance());
                if (atEnd()) {
                    throw errorHere("unterminated escape sequence");
                }
                raw.appendCodePoint(advance());
                continue;
            }
            if (cp < 0x20) {
                throw errorHere("control character U+%04X not permitted unescaped in a single-line token".formatted(cp));
            }
            if (cp == 0x0085 || cp == 0x2028 || cp == 0x2029) {
                throw errorHere("line terminator U+%04X not permitted unescaped in a single-line token; use \\u%04X".formatted(cp, cp));
            }
            raw.appendCodePoint(advance());
        }
        // A token with no escape in it is already its own text: the decode pass would copy it to say so.
        // The scanner has just read every character, so whether there was one is known rather than searched
        // for -- most quoted tokens in most documents have none.
        String text = raw.toString();
        return finish(TokenType.SINGLE_LINE_STRING, escaped ? decodeAllEscapes(text) : text);
    }

    private TokenType lexMultilineToken() {
        // Already consumed the opening """.
        skipSpacesTabs();
        if (!atEnd() && !isLineTerminatorCp(peekCodePoint())) {
            throw errorHere("content not permitted after the opening \"\"\" of a multi-line token");
        }
        if (!atEnd()) {
            consumeLineTerminator();
        }

        List<String> contentLines = new ArrayList<>();
        String closingIndent = null;

        while (true) {
            if (atEnd()) {
                throw errorAtTokenStart("unterminated multi-line token");
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
                throw errorAtTokenStart("unterminated multi-line token");
            }
            consumeLineTerminator();
        }

        String prefix = computeCommonPrefix(contentLines, closingIndent);

        StringBuilder decoded = new StringBuilder();
        for (int i = 0; i < contentLines.size(); i++) {
            String line = stripTrailing(removePrefix(contentLines.get(i), prefix));
            decoded.append(decodeAllEscapes(line));
            if (i < contentLines.size() - 1) {
                decoded.append('\n');
            }
        }
        return finish(TokenType.MULTI_LINE_STRING, decoded.toString());
    }

    /**
     * Decides whether one line of a multi-line token is the closing delimiter. Must be handed the line content
     * <em>after</em> its leading whitespace is removed (§7.2.3 lets the closing {@code """} be indented) -- testing
     * the raw line instead makes every indented closing delimiter unmatched and every multi-line token spuriously
     * "unterminated". {@code LexerTest} guards the indented case.
     */
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
    // Both always report against this token's own start (tokenStart*) -- decoding runs after the
    // live cursor has already moved past the whole token, so there's no "current position" left
    // to report against here the way the main scan loop's own errorHere() calls can.

    /**
     * {@code raw} with every escape sequence replaced by what it denotes -- <b>{@code raw} itself when it
     * holds none</b>, which is the common case and the whole reason for the check: decoding builds a second
     * copy of a token's text to discover that it already had the right one. {@link #lexSingleLineToken}
     * knows the answer without looking; a multi-line token's lines are checked here, once each.
     */
    private String decodeAllEscapes(String raw) {
        if (raw.indexOf('\\') < 0) {
            return raw;
        }
        StringBuilder sb = new StringBuilder(raw.length());
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == '\\') {
                i = decodeEscapeSequence(raw, i, sb);
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /**
     * Decodes one escape sequence starting at {@code text.charAt(i) == '\\'}. Returns the index after it.
     *
     * <p>The table is {@code \" \\ \b \f \n \r \t \s} plus the two <code>&#92;u</code> forms
     * ({@link #decodeUnicodeEscape}). <b>There is no {@code \/}</b>: a solidus needs no escaping anywhere in
     * the format, and the one reason it was ever admitted -- a JSON document had to parse unchanged -- is a
     * claim the format no longer makes.
     */
    private int decodeEscapeSequence(String text, int i, StringBuilder sb) {
        int n = text.length();
        i++; // skip backslash
        if (i >= n) {
            throw errorAtTokenStart("unterminated escape sequence");
        }
        char e = text.charAt(i);
        switch (e) {
            case '"':
                sb.append('"');
                return i + 1;
            case '\\':
                sb.append('\\');
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
                return decodeUnicodeEscape(text, i + 1, sb);
            default:
                throw errorAtTokenStart("invalid escape sequence '\\" + e + "'");
        }
    }

    /**
     * <code>&#92;u ( 4HEXDIG / "{" 1*6HEXDIG "}" )</code> (§7.2.2) -- two spellings of one number, checked by one
     * rule: <b>the value denoted must be a Unicode scalar value</b>. So a surrogate code point is refused in
     * either form, and there is nothing to pair: an escape names a character or it names nothing.
     *
     * <p>The braced form is what makes that one rule sufficient. Four hex digits cannot reach past the BMP,
     * so a format offering only them must either borrow UTF-16's pairing (three rules about how two escapes
     * combine, and the ill-formed halves each can be) or give up escaping a supplementary character at all --
     * which is the one that costs something real, plane 14 holding the variation selectors and tag characters
     * a document has every reason to write visibly rather than embed invisibly.
     */
    private int decodeUnicodeEscape(String text, int idx, StringBuilder sb) {
        int[] parsed = text.startsWith("{", idx) ? readHexBraced(text, idx + 1) : readHex4(text, idx);
        int value = parsed[0];
        if (value >= Character.MIN_SURROGATE && value <= Character.MAX_SURROGATE) {
            throw errorAtTokenStart(
                    "U+%04X is a surrogate code point, which is not a Unicode scalar value".formatted(value));
        }
        sb.appendCodePoint(value);
        return parsed[1];
    }

    /** {@code 1*6HEXDIG "}"} -- at most six digits because U+10FFFF is six, and the value must be in range. */
    private int[] readHexBraced(String text, int idx) {
        int value = 0;
        int digits = 0;
        int i = idx;
        while (i < text.length() && text.charAt(i) != '}') {
            int digit = Character.digit(text.charAt(i), 16);
            if (digit < 0) {
                throw errorAtTokenStart("invalid hex digit in unicode escape");
            }
            if (++digits > 6) {
                throw errorAtTokenStart("unicode escape has more than six hex digits");
            }
            value = (value << 4) | digit;
            i++;
        }
        if (i >= text.length()) {
            throw errorAtTokenStart("unterminated unicode escape: no closing '}'");
        }
        if (digits == 0) {
            throw errorAtTokenStart("unicode escape has no hex digits");
        }
        if (value > Character.MAX_CODE_POINT) {
            throw errorAtTokenStart("unicode escape is above the maximum code point U+10FFFF");
        }
        return new int[]{value, i + 1};
    }

    private int[] readHex4(String text, int idx) {
        if (idx + 4 > text.length()) {
            throw errorAtTokenStart("incomplete unicode escape");
        }
        int value = 0;
        for (int k = 0; k < 4; k++) {
            int digit = Character.digit(text.charAt(idx + k), 16);
            if (digit < 0) {
                throw errorAtTokenStart("invalid hex digit in unicode escape");
            }
            value = (value << 4) | digit;
        }
        return new int[]{value, idx + 4};
    }

    // ── Whitespace (§7.1, §7.2 rule 1) ──────────────────────────────────

    /**
     * Consumes the run of {@code Pattern_White_Space} before a token, holding the two halves [UAX31-R3a-1]
     * splits that property into apart.
     *
     * <p><b>LRM and RLM are not horizontal space.</b> R3a-1 sorts {@code Pattern_White_Space} into end-of-line
     * (item 1), <em>ignorable format controls</em> -- the members carrying {@code Default_Ignorable_Code_Point},
     * which the requirement's own note names as exactly U+200E and U+200F (item 2) -- and horizontal space
     * (item 3, "all other characters"). Item 2's controls "shall be allowed in the contexts UAX31-I1, UAX31-I2,
     * and UAX31-I3 ... where their insertion shall have no effect on the meaning of the program". Reading them
     * as item 3 instead is what lets {@code ad<LRM>min} lex as two tokens and {@code [1<LRM>2]} read as two
     * elements -- an insertion that plainly changes the meaning, and invisibly. §7.2 rule 1 sorts them into
     * item 2 for that reason, and §9.5 rests on it.
     *
     * <p><b>The check is R3a's own.</b> Its note states the strategy directly: "Since these characters are
     * allowed only where a boundary would, in their absence, exist between lexical elements, an implementation
     * could ignore them when lexing, and then consider as illegal any lexical element that contains them." So a
     * control is consumed and contributes nothing, and a run holding no real space is illegal exactly when the
     * code points on either side of it would have continued one token -- which is I1 (the run is adjacent to
     * horizontal space) and I2 (a space could have stood here) decided by looking at two characters.
     */
    private void skipWhitespace() {
        int precedingCodePoint = lastCodePoint;
        boolean sawHorizontalSpace = false;
        int control = -1;
        int controlLine = line;
        int controlColumn = col;
        int controlByteOffset = byteOffset;
        while (!atEnd()) {
            int cp = peekCodePoint();
            if (isIgnorableFormatControl(cp)) {
                if (control == -1) {
                    control = cp;
                    controlLine = line;
                    controlColumn = col;
                    controlByteOffset = byteOffset;
                }
            } else if (isPatternWhiteSpace(cp)) {
                sawHorizontalSpace = true;
            } else {
                break;
            }
            advance();
        }
        if (control != -1 && !sawHorizontalSpace) {
            requireTokenBoundary(precedingCodePoint, control,
                    new Position(controlLine, controlColumn, controlByteOffset));
        }
    }

    /**
     * Refuses an ignorable format control that stands inside a lexical element rather than at a boundary --
     * see {@link #skipWhitespace}. Both neighbours continuing a token is what says the two would have been one
     * token without it; a run adjacent to real horizontal space never reaches here, that being [UAX31-I1].
     */
    private void requireTokenBoundary(int precedingCodePoint, int control, Position at) {
        int following = peekCodePoint();
        if (!continuesAToken(precedingCodePoint) || !continuesAToken(following)) {
            return;
        }
        if (following == '.' && peekCodePointAt(1) == '.') {
            return; // `..` is a token of its own (§7.2 rule 3), so the boundary is there either way
        }
        throw new LexException(("%s stands between '%s' and '%s', which without it are one token -- an "
                + "ignorable format control may only stand where a token boundary already exists. Remove it, "
                + "or quote the token to keep it as content").formatted(nameOf(control),
                new String(Character.toChars(precedingCodePoint)), new String(Character.toChars(following))), at);
    }

    /** Whether {@code cp} would carry on an unquoted token -- the test for "these two would have been one token". */
    private static boolean continuesAToken(int cp) {
        return cp != -1 && isUnquotedContinuation(cp);
    }

    /** The two ignorable format controls, spelled for a message; nothing else reaches here. */
    private static String nameOf(int control) {
        return control == 0x200E ? "U+200E LEFT-TO-RIGHT MARK" : "U+200F RIGHT-TO-LEFT MARK";
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

    /**
     * §7.2 rule 1's fixed eleven-character {@code Pattern_White_Space} set. What each member <em>does</em> is
     * {@link #skipWhitespace}'s question, not this one's.
     */
    private static boolean isPatternWhiteSpace(int cp) {
        return switch (cp) {
            case 0x0009, 0x000A, 0x000B, 0x000C, 0x000D, 0x0020, 0x0085, 0x200E, 0x200F, 0x2028, 0x2029 -> true;
            default -> false;
        };
    }

    /**
     * The two members of {@code Pattern_White_Space} carrying {@code Default_Ignorable_Code_Point}, which
     * [UAX31-R3a-1] item 2 makes ignorable format controls rather than horizontal space -- its own note names
     * them: "The characters to be treated as ignorable format controls under item 2 of UAX31-R3a-1 are U+200E
     * LEFT-TO-RIGHT MARK and U+200F RIGHT-TO-LEFT MARK." Listed rather than computed from the property, for
     * the same reason the set above is: it is fixed, and two names read better than a table lookup.
     */
    private static boolean isIgnorableFormatControl(int cp) {
        return cp == 0x200E || cp == 0x200F;
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
        return peekCodePointAt(0) == -1;
    }

    private int peekCodePoint() {
        return peekCodePointAt(0);
    }

    /** Looks ahead {@code ahead} code points from the cursor without consuming; -1 past the end. Never called with more than 1, the most any lexical rule here needs. */
    private int peekCodePointAt(int ahead) {
        ensureBuffered(ahead + 1);
        return ahead < lookaheadCount ? lookaheadCodePoints[ahead] : -1;
    }

    /** Decodes code points until the lookahead holds at least {@code count} (or the input is exhausted). */
    private void ensureBuffered(int count) {
        while (lookaheadCount < count && !sourceExhausted) {
            int start = bytesDecoded;
            int cp = decodeCodePoint();
            if (cp == -1) {
                sourceExhausted = true;
            } else {
                lookaheadCodePoints[lookaheadCount] = cp;
                lookaheadByteLengths[lookaheadCount] = bytesDecoded - start;
                lookaheadCount++;
            }
        }
    }

    // ── UTF-8 (§9.1) ─────────────────────────────────────────────────────

    /**
     * One code point, decoded from {@link #source}'s bytes; -1 at end of input.
     *
     * <p><b>Decoded here rather than by a {@code Reader}</b>, for three reasons that all outlive this
     * implementation. A port to a language without Java's charset machinery has to do exactly this, so a
     * reference that hides it behind the platform's own decoder shows the one thing it cannot show. §8.1's
     * byte offset falls out of the decoding instead of being re-derived from the decoded value -- which is
     * only correct while the input is well-formed. And a decoder that reports what it rejects can reject:
     * see {@link #malformed}.
     *
     * <p>UTF-8 only. §9.1 makes it RECOMMENDED and permits UTF-16 and UTF-32; this implementation has only
     * ever read UTF-8, and the byte layer being explicit is what would make a BOM-sniffing choice of
     * decoder a local change rather than a rewrite.
     */
    private int decodeCodePoint() {
        int sequenceStart = bytesDecoded;
        int first = nextByte();
        if (first < 0) {
            return -1;
        }
        if (first < 0x80) {
            return first;
        }

        int continuations;
        int codePoint;
        if ((first & 0xE0) == 0xC0) {
            continuations = 1;
            codePoint = first & 0x1F;
        } else if ((first & 0xF0) == 0xE0) {
            continuations = 2;
            codePoint = first & 0x0F;
        } else if ((first & 0xF8) == 0xF0) {
            continuations = 3;
            codePoint = first & 0x07;
        } else {
            // A continuation byte with nothing to continue, or a 5-/6-byte form UTF-8 has never had.
            throw malformed(sequenceStart, "0x%02X is not a valid first byte of a UTF-8 sequence".formatted(first));
        }

        for (int i = 0; i < continuations; i++) {
            int next = nextByte();
            if (next < 0) {
                throw malformed(sequenceStart, "the document ends in the middle of a UTF-8 sequence");
            }
            if ((next & 0xC0) != 0x80) {
                throw malformed(sequenceStart, "0x%02X is not a UTF-8 continuation byte".formatted(next));
            }
            codePoint = (codePoint << 6) | (next & 0x3F);
        }

        // The three ways a well-formed-looking sequence still is not one. Overlong forms and encoded
        // surrogates are the classic smuggling routes -- two spellings of one character, one of which a
        // validator upstream may not have seen (§9.4's confusability concern, at the encoding layer).
        int shortestForm = switch (continuations) {
            case 1 -> 0x80;
            case 2 -> 0x800;
            default -> 0x10000;
        };
        if (codePoint < shortestForm) {
            throw malformed(sequenceStart, "U+%04X is written in %d bytes where UTF-8 requires the shortest form"
                    .formatted(codePoint, continuations + 1));
        }
        if (codePoint >= 0xD800 && codePoint <= 0xDFFF) {
            throw malformed(sequenceStart,
                    "U+%04X is a surrogate code point, which UTF-8 does not encode".formatted(codePoint));
        }
        if (codePoint > 0x10FFFF) {
            throw malformed(sequenceStart, "U+%04X is beyond the last Unicode code point".formatted(codePoint));
        }
        return codePoint;
    }

    /**
     * A byte sequence that is not UTF-8 is <b>rejected, not replaced</b>, which §7.1 requires outright: such
     * a sequence "is a lexer error ... a decoder MUST NOT substitute replacement characters (U+FFFD) and
     * continue". Silent U+FFFD replacement -- what a {@code CharsetDecoder}
     * does by default, and what this lexer used to inherit -- turns a broken byte inside a quoted token
     * into content, so a document that cannot be decoded still reads, with a value nobody wrote. For a
     * format whose identity can be a hash of its bytes, that is the wrong default.
     *
     * <p>The byte offset is the offending sequence's own first byte, exactly. Line and column name the
     * cursor, which is at most two code points behind it.
     */
    private LexException malformed(int sequenceStart, String detail) {
        return new LexException("the document is not valid UTF-8: " + detail,
                new Position(line, col, sequenceStart));
    }

    /** The next byte as an unsigned value, refilling {@link #bytes} when drained; -1 at end of input. */
    private int nextByte() {
        if (bytePosition >= byteLimit && !fillBytes()) {
            return -1;
        }
        bytesDecoded++;
        return bytes[bytePosition++] & 0xFF;
    }

    /** Refills {@link #bytes}, answering whether anything was read. */
    private boolean fillBytes() {
        try {
            int read = source.read(bytes, 0, bytes.length);
            if (read <= 0) {
                return false;
            }
            bytePosition = 0;
            byteLimit = read;
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Removes the buffered code point at the cursor <b>without counting its bytes</b>. {@link #advance()}
     * counts them first; {@link #stripLeadingBom} is the one caller that must not, §7.1 making a leading
     * BOM an encoding artifact that is discarded before lexing rather than a character at offset zero.
     */
    private void dropBufferedCodePoint() {
        lookaheadCount--;
        for (int i = 0; i < lookaheadCount; i++) {
            lookaheadCodePoints[i] = lookaheadCodePoints[i + 1];
            lookaheadByteLengths[i] = lookaheadByteLengths[i + 1];
        }
    }

    /** Consumes and returns the code point at the cursor, advancing position and line/column tracking. */
    private int advance() {
        ensureBuffered(1);
        int cp = lookaheadCodePoints[0];
        byteOffset += lookaheadByteLengths[0];
        dropBufferedCodePoint();

        if (cp == '\n' || cp == 0x0085 || cp == 0x2028 || cp == 0x2029) {
            line++;
            col = 1;
        } else if (cp == '\r') {
            if (peekCodePointAt(0) == '\n') {
                // Defer the line bump to the paired LF's own advance() call.
            } else {
                line++;
                col = 1;
            }
        } else {
            col++;
        }
        lastCodePoint = cp;
        return cp;
    }

    /** Records {@code text} as the just-lexed token's own text and returns {@code type} -- the end position needs no recording at all, it's simply wherever the live cursor now sits (see this class's own Javadoc). */
    private TokenType finish(TokenType type, String text) {
        this.tokenText = text;
        return type;
    }

    /** A {@link LexException} anchored to this token's own start -- used for a malformed token discovered anywhere within it (unterminated, invalid escape, non-NFC, ...). */
    private LexException errorAtTokenStart(String message) {
        return new LexException(message, new Position(tokenStartLine, tokenStartColumn, tokenStartByteOffset));
    }

    /** A {@link LexException} anchored to the live cursor's current position -- used mid-token, where the offending character (not the token's own start) is what a caller needs pointed at. */
    private LexException errorHere(String message) {
        return new LexException(message, new Position(line, col, byteOffset));
    }
}
