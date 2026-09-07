package io.ltr8.tson.json.lexer;

import io.ltr8.tson.base.ParseException;
import io.ltr8.tson.json.JsonPosition;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts JSON source bytes into a stream of tokens: RFC 8259 §2-§7, under the profile
 * [TSON-JSON] §3.1 states.
 *
 * <p>A single hand-written scanner over UTF-8 bytes read incrementally from an {@link InputStream},
 * <b>decoding UTF-8 itself</b> and addressed one Unicode code point at a time. That is the one place
 * this layer departs from JEP 540, whose {@code Json.parse} takes an already-decoded {@code String}
 * or {@code char[]}, and §3.1 is why: this encoding defines interchange, so the document MUST be
 * UTF-8, invalid byte sequences are an error rather than a U+FFFD substitution, and every error
 * report carries a byte offset ([TSON-DATA] §8.1) that only the byte layer can count honestly. A
 * decoder handed characters has already lost all three.
 *
 * <p>At most one code point of lookahead beyond the cursor is buffered -- no JSON token needs more --
 * so memory held is bounded regardless of document size.
 *
 * <p><b>{@link #nextToken()} returns only the {@link JsonTokenType}</b>, not a {@link JsonToken}. The
 * token's text and position are read off separately, via {@link #text()} and the six coordinate
 * accessors, valid until the next call. This avoids allocating two {@link JsonPosition} objects per
 * token whether or not a caller ever retains one; a caller wanting a snapshot builds a
 * {@link JsonToken} from the seven values itself, as {@link #tokenize()} does. It is the shape
 * {@code tson-compiler}'s own {@code Lexer} settled on, for the same measured reason.
 *
 * <p><b>What this layer does not do.</b> It produces tokens, not structure: a lexer accepts
 * {@code "] , }"} happily and the grammar layer above rejects it. It does not dedupe member names
 * either -- §3.1 makes a repeated name an error whose <em>category</em> follows the position's type,
 * which is a fact no lexer holds. And it interprets no number: {@link JsonTokenType#NUMBER} carries
 * the exact source lexeme, since §5.3 preserves digits and scale and §3.1 forbids rounding one
 * silently.
 *
 * <p>Not thread-safe; single-use over one source stream. Errors are thrown immediately
 * ({@link ParseException}), fail-fast, as the TSON lexer under the other encoding is.
 */
public final class JsonLexer {

    private final InputStream source;

    /**
     * Bytes pulled off {@link #source} in bulk, decoded one code point at a time by
     * {@link #decodeCodePoint()}. Throughput, not a lookahead window -- {@link #lookaheadCodePoints}
     * is that, and stays one code point deep.
     */
    private final byte[] bytes = new byte[512];
    private int bytePosition;
    private int byteLimit;
    private boolean sourceExhausted;

    /**
     * Bytes decoded so far -- {@link #byteOffset}'s bytes plus the lookahead's, on the same base (a
     * leading BOM counts toward neither). The difference across one {@link #decodeCodePoint()} is that
     * code point's own byte length, which is how the offset is <b>counted rather than derived</b>: a
     * length re-derived from the decoded value is right only while the input is well-formed UTF-8.
     */
    private int bytesDecoded;

    /** One code point deep: no JSON token needs to see further than the character at the cursor. */
    private final int[] lookaheadCodePoints = new int[1];
    private final int[] lookaheadByteLengths = new int[1];
    private int lookaheadCount;

    /** The live cursor: 1-based line, 1-based code-point column, 0-based UTF-8 byte offset. */
    private int line = 1;
    private int col = 1;
    private int byteOffset;

    /** The start coordinates of the token {@link #nextToken()} is currently producing. */
    private int tokenStartLine;
    private int tokenStartColumn;
    private int tokenStartByteOffset;

    /** The most recently produced token's text -- see this class's own Javadoc for when it is valid. */
    private String tokenText = "";

    /** Reused by {@link #scanString()} and {@link #scanNumber()} so a document costs one builder, not one per token. */
    private final StringBuilder scratch = new StringBuilder();

    public JsonLexer(InputStream source) {
        this.source = source;
        stripLeadingBom();
    }

    /**
     * Over an already-decoded string, for a caller who holds one -- JEP 540's own entry shape. The
     * string is encoded back to UTF-8 so that byte offsets mean what §8.1 says they mean; a caller with
     * bytes should hand over the bytes.
     */
    public JsonLexer(String source) {
        this(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)));
    }

    private static final int BOM = 0xFEFF;

    /**
     * A single leading BOM is accepted and discarded -- not counted toward {@link #line}/{@link #col}/
     * {@link #byteOffset} -- which §3.1 requires of a decoder in the same breath as forbidding an
     * encoder to emit one. A BOM anywhere else is an unrecognised character: between tokens it reaches
     * the dispatch's fallthrough, and inside a string it is ordinary content, as any other scalar is.
     */
    private void stripLeadingBom() {
        if (peekCodePoint() == BOM) {
            bytesDecoded -= lookaheadByteLengths[0];   // not a character at offset zero -- not there at all
            dropBufferedCodePoint();
        }
    }

    // ── The token stream ─────────────────────────────────────────────────

    /**
     * The next token's kind, with its text and position left on this lexer's accessors.
     *
     * <p>Returns {@link JsonTokenType#EOF} at end of input, repeatedly.
     */
    public JsonTokenType nextToken() {
        skipWhitespace();

        tokenStartLine = line;
        tokenStartColumn = col;
        tokenStartByteOffset = byteOffset;

        int cp = peekCodePoint();
        if (cp == -1) {
            return finish(JsonTokenType.EOF, "");
        }

        return switch (cp) {
            case '{' -> single(JsonTokenType.BEGIN_OBJECT, "{");
            case '}' -> single(JsonTokenType.END_OBJECT, "}");
            case '[' -> single(JsonTokenType.BEGIN_ARRAY, "[");
            case ']' -> single(JsonTokenType.END_ARRAY, "]");
            case ':' -> single(JsonTokenType.NAME_SEPARATOR, ":");
            case ',' -> single(JsonTokenType.VALUE_SEPARATOR, ",");
            case '"' -> finish(JsonTokenType.STRING, scanString());
            case 't' -> literal(JsonTokenType.TRUE, "true");
            case 'f' -> literal(JsonTokenType.FALSE, "false");
            case 'n' -> literal(JsonTokenType.NULL, "null");
            default -> {
                if (cp == '-' || (cp >= '0' && cp <= '9')) {
                    yield finish(JsonTokenType.NUMBER, scanNumber());
                }
                throw errorHere("%s is not the start of any JSON value".formatted(describe(cp)));
            }
        };
    }

    /** Every token of the document, as retainable snapshots -- a convenience for tests and small inputs, never the read path. */
    public List<JsonToken> tokenize() {
        List<JsonToken> tokens = new ArrayList<>();
        JsonTokenType type;
        do {
            type = nextToken();
            tokens.add(new JsonToken(type, tokenText, tokenStartLine, tokenStartColumn, tokenStartByteOffset,
                    line, col, byteOffset));
        } while (type != JsonTokenType.EOF);
        return tokens;
    }

    // ── Accessors for the token nextToken() most recently produced ───────

    /** The most recent token's text (§7's decoded content for a string, the exact lexeme for a number). */
    public String text() {
        return tokenText;
    }

    public int startLine() {
        return tokenStartLine;
    }

    public int startColumn() {
        return tokenStartColumn;
    }

    public int startByteOffset() {
        return tokenStartByteOffset;
    }

    /** The live cursor's line, which is the just-finished token's end -- nothing advances between the two. */
    public int endLine() {
        return line;
    }

    public int endColumn() {
        return col;
    }

    public int endByteOffset() {
        return byteOffset;
    }

    /** The just-finished token's start, materialized -- for an error a caller raises against a token it has already taken. */
    public JsonPosition start() {
        return new JsonPosition(tokenStartLine, tokenStartColumn, tokenStartByteOffset);
    }

    /** The live cursor, materialized. */
    public JsonPosition here() {
        return new JsonPosition(line, col, byteOffset);
    }

    // ── Whitespace (RFC 8259 §2) ─────────────────────────────────────────

    /**
     * Space, horizontal tab, line feed and carriage return, and nothing else. JSON's whitespace set is
     * four characters where TSON's is Pattern_White_Space's eleven; the difference is not a profile
     * choice but the two grammars', and a U+00A0 between tokens is an error here exactly as RFC 8259
     * makes it one.
     */
    private void skipWhitespace() {
        while (true) {
            int cp = peekCodePoint();
            if (cp == ' ' || cp == '\t' || cp == '\n' || cp == '\r') {
                advance();
            } else {
                return;
            }
        }
    }

    // ── Strings (RFC 8259 §7, under §3.1's well-formedness rule) ─────────

    /**
     * A quoted string, escape-decoded.
     *
     * <p>Two rules beyond RFC 8259's grammar, both §3.1's: an unescaped control character (U+0000
     * through U+001F) is an error, which the RFC itself requires; and the string MUST decode to a
     * sequence of Unicode scalar values, so an escape that names a surrogate with nothing to pair it
     * is refused rather than repaired. A well-formed {@code \\uD83D\\uDE00} pair is one character
     * here, as RFC 8259 defines it -- the pairing mechanism is JSON's, and this profile accepts JSON's
     * grammar; only the ill-formed halves are refused. (The TSON text encoding has no such mechanism:
     * [TSON-DATA] §7.2.2's escape names a scalar value or nothing. Two encodings, two escape
     * grammars, one value space.)
     */
    private String scanString() {
        advance(); // the opening quote
        scratch.setLength(0);
        while (true) {
            int cp = peekCodePoint();
            if (cp == -1) {
                throw errorAtTokenStart("the document ends inside a string");
            }
            if (cp == '"') {
                advance();
                return scratch.toString();
            }
            if (cp == '\\') {
                advance();
                scanEscape();
                continue;
            }
            if (cp < 0x20) {
                throw errorHere("%s must be escaped inside a string".formatted(describe(cp)));
            }
            advance();
            scratch.appendCodePoint(cp);
        }
    }

    /** One escape sequence, the backslash already consumed, appending what it denotes to {@link #scratch}. */
    private void scanEscape() {
        int cp = peekCodePoint();
        if (cp == -1) {
            throw errorAtTokenStart("the document ends inside a string");
        }
        switch (cp) {
            // `\/` is JSON's and stays: this is a JSON lexer. [TSON-DATA] §7.2.2 dropped it from the
            // text encoding, whose own table labelled it "(JSON compat)" -- a debt to this format that
            // this format never owed itself.
            case '"', '\\', '/' -> {
                advance();
                scratch.append((char) cp);
            }
            case 'b' -> escaped('\b');
            case 'f' -> escaped('\f');
            case 'n' -> escaped('\n');
            case 'r' -> escaped('\r');
            case 't' -> escaped('\t');
            case 'u' -> {
                advance();
                scanUnicodeEscape();
            }
            default -> throw errorHere("%s is not a JSON escape character".formatted(describe(cp)));
        }
    }

    private void escaped(char denoted) {
        advance();
        scratch.append(denoted);
    }

    /**
     * {@code \\uXXXX}, the {@code u} already consumed -- and its surrogate pairing, which is the whole
     * of §3.1's string well-formedness rule.
     *
     * <p>A high surrogate must be followed by a {@code \\u} escape naming a low one, and the pair is
     * one scalar value. A high surrogate followed by anything else, and a low surrogate standing
     * alone, name no scalar value and are errors -- the I-JSON (RFC 7493) reading, which RFC 8259
     * permits. Nothing is repaired: a JSON text encoding data no {@code text} value can hold is
     * rejected, since {@code text}'s value set is Unicode scalar sequences ([TSON-DATA] §7.2.2).
     */
    private void scanUnicodeEscape() {
        int first = hex4();
        if (Character.isLowSurrogate((char) first)) {
            throw errorHere("\\u%04X is a low surrogate with no high surrogate before it".formatted(first));
        }
        if (!Character.isHighSurrogate((char) first)) {
            scratch.append((char) first);
            return;
        }
        if (peekCodePoint() != '\\') {
            throw errorHere("\\u%04X is a high surrogate with no \\u escape after it to pair with".formatted(first));
        }
        advance();
        if (peekCodePoint() != 'u') {
            throw errorHere("\\u%04X is a high surrogate and the escape after it is not \\u".formatted(first));
        }
        advance();
        int second = hex4();
        if (!Character.isLowSurrogate((char) second)) {
            throw errorHere("\\u%04X follows the high surrogate \\u%04X and is not a low surrogate"
                    .formatted(second, first));
        }
        scratch.appendCodePoint(Character.toCodePoint((char) first, (char) second));
    }

    /** Exactly four hexadecimal digits, as a value in 0..0xFFFF. */
    private int hex4() {
        int value = 0;
        for (int i = 0; i < 4; i++) {
            int cp = peekCodePoint();
            int digit = Character.digit(cp, 16);
            if (cp == -1 || digit < 0) {
                throw errorHere("a \\u escape takes four hexadecimal digits");
            }
            advance();
            value = (value << 4) | digit;
        }
        return value;
    }

    // ── Numbers (RFC 8259 §6) ────────────────────────────────────────────

    /**
     * A number, kept as its <b>exact source lexeme</b>.
     *
     * <p>The grammar is RFC 8259's and nothing else: an optional minus, an integer part with no
     * leading zero, an optional fraction of at least one digit, an optional exponent of at least one
     * digit. No leading plus, no bare {@code .5} or {@code 5.}, no hexadecimal, no {@code NaN} or
     * {@code Infinity} -- those reach a JSON document only as strings, and only where an approximate
     * atom admits them ([TSON-JSON] §5.4).
     *
     * <p>Nothing is converted here. §3.1 imposes no precision limit and forbids rounding silently;
     * §5.3 preserves an exact number's digits and scale. Both are promises about digits, so the digits
     * are what the lexer carries -- a lexer that produced a {@code double} would have broken them
     * before any rule could keep them.
     */
    private String scanNumber() {
        scratch.setLength(0);
        if (peekCodePoint() == '-') {
            scratch.append((char) advance());
        }

        int cp = peekCodePoint();
        if (cp == '0') {
            scratch.append((char) advance());
            // Caught here rather than left to the grammar layer, which would see two numbers and complain
            // about the missing separator between them -- a message about punctuation for a document whose
            // problem is a leading zero.
            requireNoDigit("a number's integer part is a single '0' or starts with a nonzero digit");
        } else if (cp >= '1' && cp <= '9') {
            digits();
        } else {
            throw errorHere("a number needs at least one digit before its fraction or exponent");
        }

        if (peekCodePoint() == '.') {
            scratch.append((char) advance());
            requireDigit("a fraction needs at least one digit after its '.'");
            digits();
        }

        cp = peekCodePoint();
        if (cp == 'e' || cp == 'E') {
            scratch.append((char) advance());
            cp = peekCodePoint();
            if (cp == '+' || cp == '-') {
                scratch.append((char) advance());
            }
            requireDigit("an exponent needs at least one digit");
            digits();
        }
        return scratch.toString();
    }

    /** Appends the run of decimal digits at the cursor, which may be empty. */
    private void digits() {
        while (true) {
            int cp = peekCodePoint();
            if (cp < '0' || cp > '9') {
                return;
            }
            scratch.append((char) advance());
        }
    }

    private void requireDigit(String message) {
        int cp = peekCodePoint();
        if (cp < '0' || cp > '9') {
            throw errorHere(message);
        }
    }

    private void requireNoDigit(String message) {
        int cp = peekCodePoint();
        if (cp >= '0' && cp <= '9') {
            throw errorHere(message);
        }
    }

    // ── Literal names and single characters ──────────────────────────────

    /**
     * One of {@code true}/{@code false}/{@code null}, matched whole.
     *
     * <p>The failure names the literal that was nearly written rather than the character that broke
     * it: {@code nul} and {@code None} are both somebody reaching for {@code null}, and a message
     * about the letter {@code l} tells them nothing they did not already see.
     */
    private JsonTokenType literal(JsonTokenType type, String spelling) {
        for (int i = 0; i < spelling.length(); i++) {
            if (peekCodePoint() != spelling.charAt(i)) {
                throw errorAtTokenStart("'%s' is the only JSON literal that starts this way, and this is not it"
                        .formatted(spelling));
            }
            advance();
        }
        return finish(type, spelling);
    }

    private JsonTokenType single(JsonTokenType type, String spelling) {
        advance();
        return finish(type, spelling);
    }

    /** Records {@code text} as the just-lexed token's own; the end position needs no recording, being wherever the cursor now sits. */
    private JsonTokenType finish(JsonTokenType type, String text) {
        this.tokenText = text;
        return type;
    }

    // ── The cursor ───────────────────────────────────────────────────────

    /** The code point at the cursor without consuming it; -1 at end of input. */
    private int peekCodePoint() {
        ensureBuffered();
        return lookaheadCount > 0 ? lookaheadCodePoints[0] : -1;
    }

    private void ensureBuffered() {
        while (lookaheadCount < 1 && !sourceExhausted) {
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

    /** Removes the buffered code point at the cursor without counting its bytes -- {@link #stripLeadingBom}'s route. */
    private void dropBufferedCodePoint() {
        lookaheadCount--;
        for (int i = 0; i < lookaheadCount; i++) {
            lookaheadCodePoints[i] = lookaheadCodePoints[i + 1];
            lookaheadByteLengths[i] = lookaheadByteLengths[i + 1];
        }
    }

    /**
     * Consumes and returns the code point at the cursor, advancing line/column/offset tracking.
     *
     * <p>{@code \r\n} is one line break, the bump deferred to the LF's own call. A lone {@code \r} is
     * a break of its own -- and, unlike in the text encoding, NEL/LS/PS are not: they are ordinary
     * characters to RFC 8259, which knows four whitespace characters, and counting a line at one would
     * make this lexer's positions disagree with every other JSON tool's.
     */
    private int advance() {
        ensureBuffered();
        int cp = lookaheadCodePoints[0];
        byteOffset += lookaheadByteLengths[0];
        dropBufferedCodePoint();

        if (cp == '\n') {
            line++;
            col = 1;
        } else if (cp == '\r') {
            if (peekCodePoint() != '\n') {
                line++;
                col = 1;
            }
        } else {
            col++;
        }
        return cp;
    }

    // ── UTF-8 (§3.1) ─────────────────────────────────────────────────────

    /**
     * One code point, decoded from {@link #source}'s bytes; -1 at end of input.
     *
     * <p>Decoded here rather than by a {@code Reader}, for the reasons §3.1 gives: the document MUST
     * be UTF-8, an invalid sequence is an error rather than a U+FFFD substitution, and §8.1's byte
     * offset falls out of the decoding instead of being re-derived from a decoded value it can only
     * agree with while the input is well-formed. A {@code CharsetDecoder}'s default is to substitute,
     * which turns a broken byte inside a string into content nobody wrote.
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
        // validator upstream may not have seen. §10.2 is the same concern one layer up.
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

    /** The next byte as an unsigned value, refilling {@link #bytes} when drained; -1 at end of input. */
    private int nextByte() {
        if (bytePosition >= byteLimit && !fillBytes()) {
            return -1;
        }
        bytesDecoded++;
        return bytes[bytePosition++] & 0xFF;
    }

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

    // ── Errors ───────────────────────────────────────────────────────────

    /**
     * The byte offset is the offending sequence's own first byte, exactly. Line and column name the
     * cursor, which is at most one code point behind it.
     */
    private ParseException malformed(int sequenceStart, String detail) {
        return new ParseException("the document is not valid UTF-8: " + detail,
                new JsonPosition(line, col, sequenceStart));
    }

    /** Anchored to the token's own start -- for a malformed token discovered anywhere within it. */
    private ParseException errorAtTokenStart(String message) {
        return new ParseException(message, start());
    }

    /** Anchored to the live cursor -- for the offending character itself, where that is what a caller needs pointed at. */
    private ParseException errorHere(String message) {
        return new ParseException(message, here());
    }

    /** A code point named the way a reader would recognise it: as itself where it is printable, as U+XXXX where it is not. */
    private static String describe(int codePoint) {
        return codePoint > 0x20 && codePoint != 0x7F
                ? "'" + new String(Character.toChars(codePoint)) + "'"
                : "U+%04X".formatted(codePoint);
    }
}
