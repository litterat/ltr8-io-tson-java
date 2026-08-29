package io.ltr8.tson.compiler.lexer;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LexerTest {

    private static List<Token> lex(String source) {
        return new Lexer(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8))).tokenize();
    }

    /** Tokenizes and strips the trailing EOF, for tests that only care about content tokens. */
    private static List<Token> tokens(String source) {
        List<Token> all = lex(source);
        return all.subList(0, all.size() - 1);
    }

    /**
     * Type and text only, dropping positions -- for asserting that two sources lex to the <em>same tokens</em>
     * when one of them holds an ignorable format control, which legitimately shifts every later column and
     * byte offset by its own width without changing a single token.
     */
    private static List<String> shape(String source) {
        return tokens(source).stream().map(t -> t.type() + "(" + t.text() + ")").toList();
    }

    private static void assertToken(Token t, TokenType type, String text) {
        assertEquals(type, t.type());
        assertEquals(text, t.text());
    }

    // ── Whitespace and structural delimiters ────────────────────────────

    @Test
    void skipsWhitespaceBetweenTokens() {
        List<Token> ts = tokens("  {  }  ");
        assertEquals(2, ts.size());
        assertToken(ts.get(0), TokenType.LBRACE, "{");
        assertToken(ts.get(1), TokenType.RBRACE, "}");
    }

    @Test
    void structuralDelimiters() {
        List<Token> ts = tokens("{}[]:,");
        assertEquals(6, ts.size());
        assertToken(ts.get(0), TokenType.LBRACE, "{");
        assertToken(ts.get(1), TokenType.RBRACE, "}");
        assertToken(ts.get(2), TokenType.LBRACKET, "[");
        assertToken(ts.get(3), TokenType.RBRACKET, "]");
        assertToken(ts.get(4), TokenType.COLON, ":");
        assertToken(ts.get(5), TokenType.COMMA, ",");
    }

    @Test
    void noSeparatorNeededAroundDelimiters() {
        List<Token> ts = tokens("{name:Alice}");
        assertEquals(5, ts.size());
        assertToken(ts.get(0), TokenType.LBRACE, "{");
        assertToken(ts.get(1), TokenType.UNQUOTED, "name");
        assertToken(ts.get(2), TokenType.COLON, ":");
        assertToken(ts.get(3), TokenType.UNQUOTED, "Alice");
        assertToken(ts.get(4), TokenType.RBRACE, "}");
    }

    @Test
    void absentSentinel() {
        assertToken(tokens("_").get(0), TokenType.ABSENT, "_");
    }

    // ── Ignorable format controls: LRM/RLM (UAX31-R3a-1 item 2) ─────────

    /**
     * <b>LRM and RLM are not horizontal space.</b> [UAX31-R3a-1] sorts {@code Pattern_White_Space} into
     * end-of-line, ignorable format controls, and horizontal space, and its note names U+200E/U+200F as
     * exactly the second group -- "where their insertion shall have no effect on the meaning of the program".
     * Treating them as the third is what let {@code [1<LRM>2]} read as two elements: an insertion that
     * changes the meaning, invisibly, in the one position where juxtaposition is the separator and the
     * result is still a valid document. {@code SPEC-FEEDBACK.md} #16 carries the spec-side finding.
     */
    @Test
    void anIgnorableFormatControlInsideATokenIsRefused() {
        for (String control : List.of("\u200E", "\u200F")) {
            LexException thrown = assertThrows(LexException.class, () -> lex("[ 1" + control + "2 ]"));
            assertTrue(thrown.getMessage().contains("which without it are one token"), thrown.getMessage());
        }
    }

    /** Every unquoted shape it can split, not just digits -- a name, a signed number, and a dotted token. */
    @Test
    void anIgnorableFormatControlIsRefusedWhicheverTokenItSplits() {
        assertThrows(LexException.class, () -> lex("{ ad\u200Emin: 1 }"));
        assertThrows(LexException.class, () -> lex("[ alpha\u200Ebeta ]"));
        assertThrows(LexException.class, () -> lex("[ -1\u200E2 ]"));
        assertThrows(LexException.class, () -> lex("[ a\u200E.b ]"));
    }

    /** A run is one control for this purpose: what decides is the pair either side of it, however long it is. */
    @Test
    void aRunOfIgnorableFormatControlsInsideATokenIsRefused() {
        assertThrows(LexException.class, () -> lex("[ 1\u200E\u200E\u200F2 ]"));
    }

    /** The message names the character and the pair, none of which the author can see, and points at the control itself. */
    @Test
    void theRefusalNamesTheInvisibleCharacterAndItsPosition() {
        LexException thrown = assertThrows(LexException.class, () -> lex("{ ad\u200Emin: 1 }"));

        assertTrue(thrown.getMessage().contains("U+200E LEFT-TO-RIGHT MARK"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("'d' and 'm'"), thrown.getMessage());
        assertEquals(1, thrown.position().line());
        assertEquals(5, thrown.position().column());
    }

    /**
     * [UAX31-I2]: wherever horizontal space could be inserted without changing the meaning. The control is
     * consumed and contributes nothing, which is what "no effect on the meaning" asks for.
     */
    @Test
    void anIgnorableFormatControlWhereASpaceCouldStandIsIgnored() {
        assertEquals(shape("{ a: 1 }"), shape("{ a:\u200E1 }"));
        assertEquals(shape("[ 1 2 ]"), shape("[ \u200E1 2 ]"));
        assertEquals(shape("[ 1 ]"), shape("[ 1\u200E]"));
    }

    /** [UAX31-I1]: adjacent to horizontal space -- within a run of it, or at either end of one. */
    @Test
    void anIgnorableFormatControlAdjacentToHorizontalSpaceIsIgnored() {
        List<String> equivalents = List.of("[ 1 \u200E 2 ]", "[ 1\u200E 2 ]", "[ 1 \u200E2 ]");
        for (String source : equivalents) {
            assertEquals(shape("[ 1 2 ]"), shape(source), source);
        }
    }

    /** [UAX31-I3]: at the start and end of a lexical line. */
    @Test
    void anIgnorableFormatControlAtALineBoundaryIsIgnored() {
        assertEquals(shape("[ 1 2 ]"), shape("\u200E[ 1 2 ]"));
        assertEquals(shape("[ 1 2 ]"), shape("[ 1 2 ]\u200E"));
        assertEquals(shape("{ a: 1\nb: 2 }"), shape("{ a: 1\n\u200Eb: 2 }"));
    }

    /**
     * A quoted token is content, not a lexical element made of characters -- R3a's own carve-out ("except
     * comments and strings"), and the remedy §7.1 already prescribes for a name that needs one.
     */
    @Test
    void anIgnorableFormatControlInsideAQuotedTokenIsKept() {
        assertToken(tokens("\"a\u200Eb\"").getFirst(), TokenType.SINGLE_LINE_STRING, "a\u200Eb");
    }

    /**
     * {@code ..} is a token of its own (§7.2 rule 3), so a control before one stands at a boundary that was
     * already there -- the one place the "both sides continue a token" test would otherwise overreach, {@code
     * .} being an unquoted-continuation character.
     */
    @Test
    void anIgnorableFormatControlBeforeARangeTokenIsIgnored() {
        assertEquals(shape("1 .."), shape("1\u200E.."));
    }

    // ── Compound tokens ──────────────────────────────────────────────────

    @Test
    void mapArrow() {
        assertToken(tokens("=>").get(0), TokenType.MAP_ARROW, "=>");
    }

    @Test
    void bareEqualsIsNotMapArrow() {
        assertToken(tokens("=").get(0), TokenType.EQUAL, "=");
    }

    @Test
    void directive() {
        assertToken(tokens("!!").get(0), TokenType.DIRECTIVE, "!!");
    }

    @Test
    void bareBangIsTypePrefix() {
        assertToken(tokens("!").get(0), TokenType.BANG, "!");
    }

    @Test
    void rangeToken() {
        List<Token> ts = tokens("1..100");
        assertEquals(3, ts.size());
        assertToken(ts.get(0), TokenType.UNQUOTED, "1");
        assertToken(ts.get(1), TokenType.RANGE, "..");
        assertToken(ts.get(2), TokenType.UNQUOTED, "100");
    }

    @Test
    void rangeAfterLeadingDotFloat() {
        List<Token> ts = tokens(".5..2");
        assertEquals(3, ts.size());
        assertToken(ts.get(0), TokenType.UNQUOTED, ".5");
        assertToken(ts.get(1), TokenType.RANGE, "..");
        assertToken(ts.get(2), TokenType.UNQUOTED, "2");
    }

    // ── Unquoted tokens ──────────────────────────────────────────────────

    @Test
    void unquotedIdentifiersAndNumbers() {
        List<Token> ts = tokens("name 42 0xFF 2025-03-13 v1.2.3 snake_case A-100");
        String[] expected = {"name", "42", "0xFF", "2025-03-13", "v1.2.3", "snake_case", "A-100"};
        assertEquals(expected.length, ts.size());
        for (int i = 0; i < expected.length; i++) {
            assertToken(ts.get(i), TokenType.UNQUOTED, expected[i]);
        }
    }

    @Test
    void unicodeIdentifier() {
        assertToken(tokens("名前").get(0), TokenType.UNQUOTED, "名前");
    }

    @Test
    void leadingUnderscoreIsAbsentThenSeparateToken() {
        // Underscore cannot start an unquoted token; it's always the absent sentinel.
        List<Token> ts = tokens("_id");
        assertEquals(2, ts.size());
        assertToken(ts.get(0), TokenType.ABSENT, "_");
        assertToken(ts.get(1), TokenType.UNQUOTED, "id");
    }

    @Test
    void midTokenUnderscoreIsOrdinary() {
        assertToken(tokens("my_type").get(0), TokenType.UNQUOTED, "my_type");
    }

    @Test
    void signedNumbersAndHyphenatedNames() {
        List<Token> ts = tokens("-42 +0.5 a-b");
        assertToken(ts.get(0), TokenType.UNQUOTED, "-42");
        assertToken(ts.get(1), TokenType.UNQUOTED, "+0.5");
        assertToken(ts.get(2), TokenType.UNQUOTED, "a-b");
    }

    @Test
    void bareMinusIsSpecialToken() {
        List<Token> ts = tokens("1 - 2");
        assertToken(ts.get(0), TokenType.UNQUOTED, "1");
        assertToken(ts.get(1), TokenType.MINUS, "-");
        assertToken(ts.get(2), TokenType.UNQUOTED, "2");
    }

    @Test
    void barePlusIsLexError() {
        assertThrows(LexException.class, () -> lex("+"));
    }

    @Test
    void bareDotIsLexError() {
        assertThrows(LexException.class, () -> lex("."));
    }

    @Test
    void hexBlockchainAddressLexesAsUnquoted() {
        assertToken(tokens("0x71C7656EC7ab88b098defB751B7401B5f6d8976F").get(0),
                TokenType.UNQUOTED, "0x71C7656EC7ab88b098defB751B7401B5f6d8976F");
    }

    @Test
    void unquotedTokenNotNfcNormalizedIsLexError() {
        // "é" as e + combining acute accent (U+0065 U+0301) is not NFC-normalized.
        String decomposed = "café";
        assertThrows(LexException.class, () -> lex(decomposed));
    }

    // ── Special tokens ───────────────────────────────────────────────────

    @Test
    void typeAnnotationAndAnnotationPrefixes() {
        List<Token> ts = tokens("!uuid @deprecated");
        assertToken(ts.get(0), TokenType.BANG, "!");
        assertToken(ts.get(1), TokenType.UNQUOTED, "uuid");
        assertToken(ts.get(2), TokenType.AT, "@");
        assertToken(ts.get(3), TokenType.UNQUOTED, "deprecated");
    }

    @Test
    void reservedSpecialCharacters() {
        List<Token> ts = tokens("&<>?~|;()^");
        TokenType[] expected = {
                TokenType.AMPERSAND, TokenType.LESS_THAN, TokenType.GREATER_THAN, TokenType.QUESTION,
                TokenType.TILDE, TokenType.PIPE, TokenType.SEMICOLON, TokenType.LPAREN, TokenType.RPAREN,
                TokenType.CARET
        };
        assertEquals(expected.length, ts.size());
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], ts.get(i).type());
        }
    }

    @Test
    void unrecognisedCharacterIsLexError() {
        assertThrows(LexException.class, () -> lex("$"));
        assertThrows(LexException.class, () -> lex("#"));
        assertThrows(LexException.class, () -> lex("%"));
        assertThrows(LexException.class, () -> lex("/"));
    }

    // ── Single-line quoted tokens ────────────────────────────────────────

    @Test
    void simpleQuotedString() {
        assertToken(tokens("\"has spaces\"").get(0), TokenType.SINGLE_LINE_STRING, "has spaces");
    }

    @Test
    void quotedStringLooksLikeNumberButIsString() {
        assertToken(tokens("\"42\"").get(0), TokenType.SINGLE_LINE_STRING, "42");
    }

    @Test
    void allSingleCharEscapes() {
        String source = "\"\\\" \\\\ \\/ \\b \\f \\n \\r \\t \\s\"";
        String expected = "\" \\ / \b \f \n \r \t  "; // trailing \s decodes to a literal space
        assertToken(tokens(source).get(0), TokenType.SINGLE_LINE_STRING, expected);
    }

    @Test
    void unicodeEscapeBmp() {
        assertToken(tokens("\"\\u0041\"").get(0), TokenType.SINGLE_LINE_STRING, "A");
    }

    @Test
    void unicodeEscapeSurrogatePair() {
        // U+1F600 GRINNING FACE, encoded as a UTF-16 surrogate pair escape.
        Token t = tokens("\"\\uD83D\\uDE00\"").get(0);
        assertEquals(TokenType.SINGLE_LINE_STRING, t.type());
        assertEquals(0x1F600, t.text().codePointAt(0));
    }

    @Test
    void loneHighSurrogateEscapeIsLexError() {
        assertThrows(LexException.class, () -> lex("\"\\uD800\""));
    }

    @Test
    void loneLowSurrogateEscapeIsLexError() {
        assertThrows(LexException.class, () -> lex("\"\\uDC00\""));
    }

    @Test
    void invalidEscapeIsLexError() {
        assertThrows(LexException.class, () -> lex("\"\\x\""));
    }

    @Test
    void unterminatedSingleLineTokenIsLexError() {
        assertThrows(LexException.class, () -> lex("\"abc"));
    }

    @Test
    void literalTabInSingleLineTokenIsLexError() {
        assertThrows(LexException.class, () -> lex("\"a\tb\""));
    }

    @Test
    void unescapedLineSeparatorInSingleLineTokenIsLexError() {
        assertThrows(LexException.class, () -> lex("\"a\u2028b\""));
    }

    @Test
    void emailAddressRequiresQuoting() {
        assertToken(tokens("\"alice@example.com\"").get(0), TokenType.SINGLE_LINE_STRING, "alice@example.com");
    }

    // ── Multi-line quoted tokens ─────────────────────────────────────────

    @Test
    void multilineBasicIndentStripping() {
        String source = "\"\"\"\n    Leave the parcel with the concierge.\n    Gift wrap.\n    \"\"\"";
        Token t = tokens(source).get(0);
        assertEquals(TokenType.MULTI_LINE_STRING, t.type());
        assertEquals("Leave the parcel with the concierge.\nGift wrap.", t.text());
    }

    @Test
    void multilineTabIndentIsPreservedAsLiteralContent() {
        // Tabs are permitted as literal content in multi-line tokens (unlike single-line).
        String source = "\"\"\"\n\tline one\n\t\"\"\"";
        Token t = tokens(source).get(0);
        assertEquals("line one", t.text());
    }

    @Test
    void multilineTabNeverMatchesSpaceInCommonPrefix() {
        // First content line indented with a space, second with a tab: no common prefix.
        String source = "\"\"\"\n line-a\n\tline-b\n\"\"\"";
        Token t = tokens(source).get(0);
        assertEquals(" line-a\n\tline-b", t.text());
    }

    @Test
    void multilineBlankLinesExcludedFromPrefixCalculation() {
        String source = "\"\"\"\n    a\n\n    b\n    \"\"\"";
        Token t = tokens(source).get(0);
        assertEquals("a\n\nb", t.text());
    }

    @Test
    void multilineTrailingWhitespaceStrippedUnlessEscaped() {
        String source = "\"\"\"\n    a  \n    b\\s\\s\n    \"\"\"";
        Token t = tokens(source).get(0);
        assertEquals("a\nb  ", t.text());
    }

    @Test
    void multilineEmbeddedDoubleQuotesAreLiteral() {
        String source = "\"\"\"\n    a \" b \"\" c\n    \"\"\"";
        Token t = tokens(source).get(0);
        assertEquals("a \" b \"\" c", t.text());
    }

    @Test
    void multilineEscapedTripleQuoteIsLiteralContent() {
        String source = "\"\"\"\n    \\\"\"\"\n    \"\"\"";
        Token t = tokens(source).get(0);
        assertEquals("\"\"\"", t.text());
    }

    @Test
    void multilineContentAfterOpeningDelimiterIsLexError() {
        assertThrows(LexException.class, () -> lex("\"\"\"not allowed\n\"\"\""));
    }

    @Test
    void unterminatedMultilineTokenIsLexError() {
        assertThrows(LexException.class, () -> lex("\"\"\"\nabc"));
    }

    // ── Full-document example (adapted from spec §2.1) ──────────────────

    @Test
    void orderDocumentSnippetLexesWithoutError() {
        String doc = """
                !order {
                  order_id:  1042
                  reference: !uuid 9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09
                  customer: {
                    name:  "Ada Lovelace"
                    tier:  @deprecated GOLD
                  }
                  flags:   0b0110
                  items: [
                    { sku: A-100 qty: 2 price: 49.95 discount: .5 }
                    { sku: B-205 qty: 1 price: 100.00 discount: _ }
                  ]
                  discounts: { WELCOME10 => "10%" loyalty => _ }
                }
                """;
        List<Token> ts = lex(doc);
        assertEquals(TokenType.EOF, ts.get(ts.size() - 1).type());
        // A representative slice: !, order, {, order_id, :, 1042 ...
        assertToken(ts.get(0), TokenType.BANG, "!");
        assertToken(ts.get(1), TokenType.UNQUOTED, "order");
        assertToken(ts.get(2), TokenType.LBRACE, "{");
        assertToken(ts.get(3), TokenType.UNQUOTED, "order_id");
        assertToken(ts.get(4), TokenType.COLON, ":");
        assertToken(ts.get(5), TokenType.UNQUOTED, "1042");
    }

    // ── Position tracking ────────────────────────────────────────────────

    @Test
    void positionsTrackLineAndColumn() {
        List<Token> ts = tokens("a\nb");
        assertEquals(1, ts.get(0).start().line());
        assertEquals(1, ts.get(0).start().column());
        assertEquals(2, ts.get(1).start().line());
        assertEquals(1, ts.get(1).start().column());
    }

    @Test
    void crlfCountsAsOneLine() {
        List<Token> ts = tokens("a\r\nb");
        assertEquals(1, ts.get(0).start().line());
        assertEquals(2, ts.get(1).start().line());
    }

    // ── BOM handling ─────────────────────────────────────────────────────

    @Test
    void leadingBomIsStrippedAndNotEmittedAsAToken() {
        List<Token> ts = tokens("\uFEFF{}");
        assertEquals(2, ts.size());
        assertToken(ts.get(0), TokenType.LBRACE, "{");
    }

    @Test
    void bomOutsideLeadingPositionIsLexError() {
        assertThrows(LexException.class, () -> lex("{\uFEFF}"));
    }

    // ── Reading in blocks: nothing may depend on where a refill lands ────

    /**
     * The lexer pulls characters off its reader a block at a time rather than one at a time (which cost a
     * {@code CharBuffer} per character), so every lexical rule now runs across a refill boundary that is
     * invisible to it. These pin that invisibility: the block is an implementation detail of reading, not a
     * limit on tokens, and a rule must not care where it falls.
     */
    @Test
    void aTokenLongerThanTheReadBlockLexesWhole() {
        String text = "a".repeat(5_000);

        List<Token> ts = tokens("\"" + text + "\"");

        assertEquals(1, ts.size());
        assertToken(ts.get(0), TokenType.SINGLE_LINE_STRING, text);
    }

    /** Every token boundary walked across the refill point, one character at a time. */
    @Test
    void everyTokenBoundaryStraddlingARefillLexesTheSame() {
        for (int padding = 490; padding <= 540; padding++) {
            String source = "{ pad: \"" + "x".repeat(padding) + "\"  n: 12345  t: !uuid tail }";

            List<Token> ts = tokens(source);

            assertToken(ts.get(3), TokenType.SINGLE_LINE_STRING, "x".repeat(padding));
            assertToken(ts.get(6), TokenType.UNQUOTED, "12345");
            assertToken(ts.get(ts.size() - 2), TokenType.UNQUOTED, "tail");
        }
    }

    /**
     * The one case a block read could genuinely break: a surrogate pair whose halves land in different
     * blocks. The lexer reassembles a code point from two chars, and the second may be the one that
     * triggers the refill.
     */
    @Test
    void aSurrogatePairSplitAcrossARefillIsOneCodePoint() {
        String emoji = "\uD83D\uDE80";   // U+1F680, a surrogate pair
        for (int padding = 500; padding <= 530; padding++) {
            String text = "x".repeat(padding) + emoji + "y";
            String source = "\"" + text + "\"";

            List<Token> ts = tokens(source);

            assertEquals(1, ts.size(), "padding " + padding);
            assertToken(ts.get(0), TokenType.SINGLE_LINE_STRING, text);
        }
    }

    /** Positions are counted in code points and UTF-8 bytes, neither of which knows about a refill. */
    @Test
    void positionsAreUnaffectedByWhereARefillLands() {
        String padding = "x".repeat(600);
        String source = "\"" + padding + "\"\nsecond";

        List<Token> ts = tokens(source);

        Token second = ts.get(1);
        assertEquals(2, second.startLine());
        assertEquals(1, second.startColumn());
        assertEquals(padding.length() + 3, second.startByteOffset(), "two quotes and a newline");
    }

    // ── UTF-8 decoding (§9.1), done here rather than by a platform decoder ──

    private static List<Token> lexBytes(int... unsigned) {
        byte[] raw = new byte[unsigned.length];
        for (int i = 0; i < unsigned.length; i++) {
            raw[i] = (byte) unsigned[i];
        }
        return new Lexer(new ByteArrayInputStream(raw)).tokenize();
    }

    private static void assertNotUtf8(String why, int... unsigned) {
        LexException thrown = assertThrows(LexException.class, () -> lexBytes(unsigned), why);
        assertTrue(thrown.getMessage().startsWith("the document is not valid UTF-8"), thrown.getMessage());
    }

    /**
     * Bytes that are not UTF-8 are refused, not replaced. A replacing decoder -- the platform default, and
     * what this lexer used to inherit -- turns the same broken byte into an error outside a quoted token
     * and into silent content inside one. §7.1: a decoder MUST NOT substitute U+FFFD and continue.
     */
    @Test
    void refusesBytesThatAreNotUtf8() {
        assertNotUtf8("a continuation byte with nothing to continue", 0x80);
        assertNotUtf8("a two-byte sequence cut off by end of input", 0xC3);
        assertNotUtf8("a second byte that is not a continuation", 0xC3, 0x28);
        assertNotUtf8("a five-byte form, which UTF-8 has never had", 0xF8, 0x88, 0x80, 0x80, 0x80);
    }

    /** Two spellings of one character is the encoding layer's confusability problem (§9.4's, one level down). */
    @Test
    void refusesOverlongFormsAndEncodedSurrogates() {
        assertNotUtf8("'/' written in two bytes instead of one", 0xC0, 0xAF);
        assertNotUtf8("U+0000 written in two bytes", 0xC0, 0x80);
        assertNotUtf8("U+D800, a surrogate, encoded as if it were a character", 0xED, 0xA0, 0x80);
        assertNotUtf8("a value beyond U+10FFFF", 0xF5, 0x80, 0x80, 0x80);
    }

    /** The case a replacing decoder gets most wrong: the document reads, with a character nobody wrote. */
    @Test
    void refusesAMalformedByteInsideAQuotedToken() {
        assertNotUtf8("inside a quoted token", '"', 'a', 0xFF, 'b', '"');
    }

    @Test
    void decodesEveryUtf8SequenceLength() {
        List<Token> ts = tokens("\"a\u00E9\u20AC\uD83D\uDE80\"");   // 1-, 2-, 3- and 4-byte sequences

        assertEquals(1, ts.size());
        assertToken(ts.get(0), TokenType.SINGLE_LINE_STRING, "a\u00E9\u20AC\uD83D\uDE80");
    }

    /**
     * §8.1 requires a byte offset in every error report, and it is counted from the input rather than
     * re-derived from the decoded character -- so it stays right for multi-byte characters, and would stay
     * right for a malformed sequence, where a derived length is exactly wrong.
     */
    @Test
    void byteOffsetsCountTheInputsOwnBytes() {
        List<Token> ts = tokens("\"\u00E9\u20AC\uD83D\uDE80\" second");

        Token second = ts.get(1);
        assertEquals(12, second.startByteOffset(), "two quotes, 2 + 3 + 4 bytes of content, and a space");
        assertEquals(7, second.startColumn(), "columns count code points: quote, three characters, quote, space");
    }

    /** A BOM is an encoding artifact discarded before lexing (§7.1) -- it is not the character at offset zero. */
    @Test
    void aLeadingBomCountsTowardNoOffset() {
        List<Token> ts = lexBytes(0xEF, 0xBB, 0xBF, 'a', 'b');

        assertEquals(0, ts.get(0).startByteOffset());
        assertEquals(1, ts.get(0).startColumn());
        assertToken(ts.get(0), TokenType.UNQUOTED, "ab");
    }

    // ── Escape decoding: the copy a token without escapes does not need ──

    /**
     * A quoted token holding no escape is its own text, and the decode pass is skipped rather than run to
     * discover that. These are the characters that most look like they might be escape syntax and are not.
     */
    @Test
    void aQuotedTokenWithNoEscapesKeepsItsTextExactly() {
        for (String content : List.of("plain", "with spaces", "a/b", "~0~1", "$100 & 50%", "caf\u00E9 \u4E2D\u6587",
                "trailing backslash-free", "100% \u2014 done")) {
            List<Token> ts = tokens("\"" + content + "\"");

            assertEquals(1, ts.size(), content);
            assertToken(ts.get(0), TokenType.SINGLE_LINE_STRING, content);
        }
    }

    /** Escapes still decode, and the fast path must not shadow a token that mixes escaped and plain text. */
    @Test
    void aQuotedTokenMixingPlainTextAndEscapesDecodesFully() {
        List<Token> ts = tokens("\"plain\\tthen\\u0041 more/text\"");

        assertToken(ts.get(0), TokenType.SINGLE_LINE_STRING, "plain\tthenA more/text");
    }

    /** A multi-line token decodes line by line, so one token may take both paths. */
    @Test
    void aMultiLineTokenMixesEscapedAndUnescapedLines() {
        List<Token> ts = tokens("\"\"\"\nplain line\nescaped\\tline\nplain again\n\"\"\"");

        assertToken(ts.get(0), TokenType.MULTI_LINE_STRING, "plain line\nescaped\tline\nplain again");
    }
}
