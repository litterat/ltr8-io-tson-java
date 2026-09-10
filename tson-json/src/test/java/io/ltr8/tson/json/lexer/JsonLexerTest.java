package io.ltr8.tson.json.lexer;

import io.ltr8.tson.base.io.ByteSource;
import io.ltr8.tson.base.ParseException;
import io.ltr8.tson.json.JsonPosition;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lexical layer of the JSON encoding: RFC 8259's token grammar under [TSON-JSON] §3.1's profile.
 *
 * <p>Structural questions are not asked here -- {@code "] : ,"} lexes clean and the grammar layer is
 * what refuses it.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class JsonLexerTest {

    private static List<JsonToken> tokens(String source) {
        return new JsonLexer(ByteSource.of(utf8(source))).tokenize();
    }

    private static List<JsonToken> tokens(byte... source) {
        return new JsonLexer(ByteSource.of(new ByteArrayInputStream(source))).tokenize();
    }

    private static List<JsonTokenType> types(String source) {
        return tokens(source).stream().map(JsonToken::type).toList();
    }

    /** The one token a source holds, {@link JsonTokenType#EOF} aside. */
    private static JsonToken only(String source) {
        List<JsonToken> tokens = tokens(source);
        assertEquals(2, tokens.size(), () -> "expected one token plus EOF, got " + tokens);
        assertEquals(JsonTokenType.EOF, tokens.get(1).type());
        return tokens.getFirst();
    }

    private static ParseException refused(String source) {
        return assertThrows(ParseException.class, () -> tokens(source));
    }

    @Nested
    class Structure {

        @Test
        void the_six_structural_characters_each_lex_to_their_own_kind() {
            assertEquals(List.of(JsonTokenType.BEGIN_OBJECT, JsonTokenType.END_OBJECT,
                            JsonTokenType.BEGIN_ARRAY, JsonTokenType.END_ARRAY,
                            JsonTokenType.NAME_SEPARATOR, JsonTokenType.VALUE_SEPARATOR, JsonTokenType.EOF),
                    types("{}[]:,"));
        }

        @Test
        void an_empty_document_is_just_end_of_input() {
            assertEquals(List.of(JsonTokenType.EOF), types(""));
        }

        @Test
        void whitespace_is_the_four_characters_rfc_8259_names_and_no_others() {
            assertEquals(List.of(JsonTokenType.BEGIN_ARRAY, JsonTokenType.END_ARRAY, JsonTokenType.EOF),
                    types(" \t\r\n[ \t\r\n] \t\r\n"));
        }

        @Test
        void a_non_json_whitespace_character_between_tokens_is_refused() {
            // U+00A0 NO-BREAK SPACE is Pattern_White_Space to the TSON lexer and nothing at all to this one.
            assertTrue(refused("[\u00A01]").getMessage().contains("not the start of any JSON value"));
        }

        @Test
        void nothing_structural_is_read_here_so_a_stray_closer_lexes_clean() {
            assertEquals(List.of(JsonTokenType.END_ARRAY, JsonTokenType.NAME_SEPARATOR,
                    JsonTokenType.VALUE_SEPARATOR, JsonTokenType.EOF), types("] : ,"));
        }
    }

    @Nested
    class Literals {

        @Test
        void the_three_literal_names_lex_whole() {
            assertEquals(List.of(JsonTokenType.TRUE, JsonTokenType.FALSE, JsonTokenType.NULL, JsonTokenType.EOF),
                    types("true false null"));
            assertEquals("null", only("null").text());
        }

        @Test
        void a_misspelled_literal_names_the_literal_it_was_reaching_for() {
            assertTrue(refused("nul").getMessage().contains("'null'"));
            assertTrue(refused("True").getMessage().contains("not the start of any JSON value"));
            assertTrue(refused("fasle").getMessage().contains("'false'"));
        }

        @Test
        void a_misspelled_literal_reports_at_its_own_start_not_at_the_letter_that_broke_it() {
            assertEquals(new JsonPosition(1, 5, 4), refused("[1, nul]").position());
        }
    }

    @Nested
    class Numbers {

        @Test
        void a_number_keeps_its_exact_source_lexeme() {
            // §5.3: digits and scale are preserved, so `199.90` must not become `199.9` -- which it would
            // the moment anything under this converted it to a host type. The lexer converts nothing.
            assertEquals("199.90", only("199.90").text());
            assertEquals("6.02e23", only("6.02e23").text());
            assertEquals("-0.0", only("-0.0").text());
            assertEquals("1E+2", only("1E+2").text());
        }

        @Test
        void arbitrary_precision_survives_because_nothing_funnels_it_through_binary64() {
            String digits = "1234567890123456789012345678901234567890.12345678901234567890";
            assertEquals(digits, only(digits).text());
            assertEquals("9007199254740993", only("9007199254740993").text());
        }

        @Test
        void the_forms_rfc_8259_admits() {
            assertEquals(JsonTokenType.NUMBER, only("0").type());
            assertEquals(JsonTokenType.NUMBER, only("-0").type());
            assertEquals(JsonTokenType.NUMBER, only("0.5").type());
            assertEquals(JsonTokenType.NUMBER, only("1e-7").type());
        }

        @Test
        void a_leading_zero_is_refused_where_it_stands_rather_than_left_to_the_grammar() {
            assertTrue(refused("01").getMessage().contains("single '0' or starts with a nonzero digit"));
        }

        @Test
        void the_forms_rfc_8259_does_not_admit() {
            assertTrue(refused("+1").getMessage().contains("not the start of any JSON value"));
            assertTrue(refused(".5").getMessage().contains("not the start of any JSON value"));
            assertTrue(refused("5.").getMessage().contains("fraction needs at least one digit"));
            assertTrue(refused("1e").getMessage().contains("exponent needs at least one digit"));
            assertTrue(refused("1e+").getMessage().contains("exponent needs at least one digit"));
            assertTrue(refused("-").getMessage().contains("at least one digit"));
        }

        @Test
        void the_special_values_have_no_number_spelling_here() {
            // §5.4 puts `.nan` and the infinities in *strings* at approximate positions, so a bare
            // NaN/Infinity is not a JSON number and this lexer must not invent one.
            assertTrue(refused("NaN").getMessage().contains("not the start of any JSON value"));
            assertTrue(refused("Infinity").getMessage().contains("not the start of any JSON value"));
            assertTrue(refused("-Infinity").getMessage().contains("at least one digit"));
            // `0x1F` lexes the `0` -- a whole, legal JSON number -- and stops; `x` is then what nothing
            // admits. Hexadecimal is a TSON text spelling ([TSON-JSON] §4.3) and reaches JSON as nothing.
            assertTrue(refused("0x1F").getMessage().contains("'x' is not the start of any JSON value"));
        }
    }

    @Nested
    class Strings {

        @Test
        void a_string_carries_its_decoded_content() {
            assertEquals("hello", only("\"hello\"").text());
            assertEquals("", only("\"\"").text());
        }

        @Test
        void every_escape_rfc_8259_defines_decodes_including_the_solidus_tson_text_dropped() {
            // [TSON-DATA] §7.2.2 dropped `\/` from the text encoding, its own table having labelled it
            // "(JSON compat)". It is JSON's escape and it stays here: this is a JSON lexer.
            assertEquals("\" \\ / \b \f \n \r \t", only("\"\\\" \\\\ \\/ \\b \\f \\n \\r \\t\"").text());
        }

        @Test
        void a_u_escape_names_a_code_unit() {
            assertEquals("A", only("\"\\u0041\"").text());
            assertEquals("\u00E9", only("\"\\u00e9\"").text());
        }

        @Test
        void a_well_formed_surrogate_pair_is_one_character() {
            // The pairing mechanism is JSON's and this profile accepts JSON's grammar (§3.1); only the
            // ill-formed halves are refused. TSON text has no pairing at all -- one scalar escape, one rule.
            String decoded = only("\"\\uD83D\\uDE00\"").text();
            assertEquals(1, decoded.codePointCount(0, decoded.length()));
            assertEquals(0x1F600, decoded.codePointAt(0));
        }

        @Test
        void an_escape_that_would_decode_to_a_lone_surrogate_is_refused_not_repaired() {
            assertTrue(refused("\"\\uD83D\"").getMessage().contains("no \\u escape after it"));
            assertTrue(refused("\"\\uDE00\"").getMessage().contains("no high surrogate before it"));
            assertTrue(refused("\"\\uD83Dx\"").getMessage().contains("no \\u escape after it"));
            assertTrue(refused("\"\\uD83D\\u0041\"").getMessage().contains("is not a low surrogate"));
            assertTrue(refused("\"\\uD83D\\n\"").getMessage().contains("is not \\u"));
        }

        @Test
        void an_unknown_escape_is_refused() {
            assertTrue(refused("\"\\x\"").getMessage().contains("not a JSON escape character"));
            assertTrue(refused("\"\\u00G0\"").getMessage().contains("four hexadecimal digits"));
            assertTrue(refused("\"\\u00\"").getMessage().contains("four hexadecimal digits"));
        }

        @Test
        void a_raw_control_character_inside_a_string_is_refused() {
            assertTrue(refused("\"a\nb\"").getMessage().contains("U+000A"));
            assertTrue(refused("\"a\u0000b\"").getMessage().contains("U+0000"));
            assertTrue(refused("\"a\tb\"").getMessage().contains("U+0009"));
        }

        @Test
        void an_unterminated_string_reports_at_the_opening_quote() {
            ParseException e = refused("{\"a\": \"unterminated}");
            assertTrue(e.getMessage().contains("ends inside a string"));
            assertEquals(new JsonPosition(1, 7, 6), e.position());
        }

        @Test
        void content_needing_a_multi_line_token_in_tson_text_is_ordinary_escaped_content_here() {
            // §5.6: "no information distinguishes the forms".
            assertEquals("line\none \"quoted\"", only("\"line\\none \\\"quoted\\\"\"").text());
        }
    }

    @Nested
    class Utf8 {

        @Test
        void multi_byte_characters_decode_and_count_one_column_each() {
            JsonToken token = only("\"\u00E9\u4E2D\uD83D\uDE00\"");
            assertEquals("\u00E9\u4E2D\uD83D\uDE00", token.text());
            // Six code points: quote, e-acute, the CJK character, the emoji, quote -- five, so the
            // cursor lands in column 6. Columns count code points, never UTF-16 units or bytes.
            assertEquals(6, token.endColumn());
            // 1 + 2 + 3 + 4 + 1 bytes.
            assertEquals(11, token.endByteOffset());
        }

        @Test
        void an_invalid_byte_sequence_is_an_error_rather_than_a_replacement_character() {
            // §3.1: "invalid byte sequences are lexer errors ... no replacement characters, no
            // continuation". A CharsetDecoder's default would turn this into content nobody wrote.
            ParseException e = assertThrows(ParseException.class,
                    () -> tokens((byte) '"', (byte) 0xC3, (byte) 0x28, (byte) '"'));
            assertTrue(e.getMessage().contains("not valid UTF-8"));
            assertTrue(e.getMessage().contains("not a UTF-8 continuation byte"));
        }

        @Test
        void the_classic_smuggling_forms_are_refused() {
            // An overlong encoding of '/', and a surrogate half encoded in UTF-8: two spellings of one
            // thing, one of which a validator upstream may never have seen.
            assertTrue(assertThrows(ParseException.class,
                    () -> tokens((byte) 0xC0, (byte) 0xAF)).getMessage().contains("shortest form"));
            assertTrue(assertThrows(ParseException.class,
                    () -> tokens((byte) 0xED, (byte) 0xA0, (byte) 0x80)).getMessage().contains("surrogate code point"));
        }

        @Test
        void a_truncated_sequence_at_end_of_input_is_refused() {
            assertTrue(assertThrows(ParseException.class,
                    () -> tokens((byte) 0xE4, (byte) 0xB8)).getMessage().contains("ends in the middle"));
        }

        @Test
        void a_malformed_sequence_reports_the_offset_of_its_own_first_byte() {
            byte[] source = "[1, ".getBytes(UTF_8);
            byte[] withBadByte = new byte[source.length + 1];
            System.arraycopy(source, 0, withBadByte, 0, source.length);
            withBadByte[source.length] = (byte) 0xFF;
            assertEquals(4, assertThrows(ParseException.class,
                    () -> tokens(withBadByte)).position().byteOffset());
        }

        @Test
        void a_single_leading_bom_is_discarded_and_counts_toward_nothing() {
            JsonToken first = tokens("\uFEFF[1]").getFirst();
            assertEquals(JsonTokenType.BEGIN_ARRAY, first.type());
            assertEquals(new JsonPosition(1, 1, 0), first.start());
        }

        @Test
        void a_bom_that_is_not_leading_is_an_ordinary_character() {
            // Inside a string it is content; between tokens it is not the start of any JSON value.
            assertEquals("\uFEFF", only("\"\uFEFF\"").text());
            assertTrue(refused("[\uFEFF1]").getMessage().contains("not the start of any JSON value"));
        }
    }

    @Nested
    class Positions {

        @Test
        void line_column_and_byte_offset_track_together() {
            List<JsonToken> tokens = tokens("{\n  \"a\": 1\n}");
            assertEquals(new JsonPosition(1, 1, 0), tokens.get(0).start());     // {
            assertEquals(new JsonPosition(2, 3, 4), tokens.get(1).start());     // "a"
            assertEquals(new JsonPosition(2, 6, 7), tokens.get(2).start());     // :
            assertEquals(new JsonPosition(2, 8, 9), tokens.get(3).start());     // 1
            assertEquals(new JsonPosition(3, 1, 11), tokens.get(4).start());    // }
        }

        @Test
        void a_crlf_pair_is_one_line_break() {
            List<JsonToken> tokens = tokens("[\r\n1\r\n]");
            assertEquals(2, tokens.get(1).startLine());
            assertEquals(3, tokens.get(2).startLine());
        }

        @Test
        void a_lone_cr_is_a_line_break_of_its_own() {
            assertEquals(2, tokens("[\r1]").get(1).startLine());
        }

        @Test
        void the_line_separators_the_tson_lexer_counts_are_not_line_breaks_here() {
            // U+2028/U+2029 are line terminators to [TSON-DATA] §7.2 and ordinary characters to RFC 8259,
            // which knows four whitespace characters. Counting a line at one would put this lexer's
            // positions out of step with every other JSON tool's.
            assertEquals("\u2028\u2029", only("\"\u2028\u2029\"").text());
            assertEquals(1, only("\"\u2028\u2029\"").endLine());
        }

        @Test
        void end_of_input_reports_the_position_after_the_last_token() {
            List<JsonToken> tokens = tokens("[1]");
            assertEquals(new JsonPosition(1, 4, 3), tokens.getLast().start());
        }

        @Test
        void a_message_states_what_went_wrong_and_the_position_states_where() {
            ParseException e = refused("[1, +2]");
            assertTrue(e.getMessage().contains("'+'"));
            assertTrue(!e.getMessage().contains("line"), () -> "the message states where: " + e.getMessage());
            assertEquals(new JsonPosition(1, 5, 4), e.position());
            // The shared ParseException spells line and column; JsonPosition keeps JEP 540's "position"
            // wording for its own toString, which is where a consumer prints one directly.
            assertTrue(e.toString().contains("line 1, column 5"));
        }
    }

    @Nested
    class TokenStream {

        @Test
        void end_of_input_repeats() {
            JsonLexer lexer = new JsonLexer(ByteSource.of(utf8("1")));
            assertEquals(JsonTokenType.NUMBER, lexer.nextToken());
            assertEquals(JsonTokenType.EOF, lexer.nextToken());
            assertEquals(JsonTokenType.EOF, lexer.nextToken());
        }

        @Test
        void the_accessors_describe_whichever_token_was_produced_last() {
            JsonLexer lexer = new JsonLexer(ByteSource.of(utf8("  \"ab\"")));
            assertEquals(JsonTokenType.STRING, lexer.nextToken());
            assertEquals("ab", lexer.text());
            assertEquals(3, lexer.startColumn());
            assertEquals(2, lexer.startByteOffset());
            assertEquals(7, lexer.endColumn());
            assertEquals(6, lexer.endByteOffset());
            assertEquals(1, lexer.startLine());
            assertEquals(1, lexer.endLine());
        }

        @Test
        void a_document_read_from_bytes_and_the_same_document_read_from_a_string_lex_alike() {
            String source = "{\"\u00E9\": [1, true, null]}";
            assertEquals(tokens(source), tokens(source.getBytes(UTF_8)));
        }

        @Test
        void a_document_longer_than_the_read_buffer_lexes_whole() {
            // The byte buffer is throughput, not a window: a token straddling a refill must not split.
            String source = "\"" + "x".repeat(4096) + "\"";
            assertEquals(4096, only(source).text().length());
        }
    }

    /** §3.1 makes the document UTF-8 and the lexer takes bytes; a test holding a string says so here. */
    private static java.io.InputStream utf8(String source) {
        return new java.io.ByteArrayInputStream(source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
