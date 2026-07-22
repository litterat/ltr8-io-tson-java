package io.ltr8.tson.parser.lexer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LexerTest {

    private static List<Token> lex(String source) {
        return new Lexer(source).tokenize();
    }

    /** Tokenizes and strips the trailing EOF, for tests that only care about content tokens. */
    private static List<Token> tokens(String source) {
        List<Token> all = lex(source);
        return all.subList(0, all.size() - 1);
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
}
