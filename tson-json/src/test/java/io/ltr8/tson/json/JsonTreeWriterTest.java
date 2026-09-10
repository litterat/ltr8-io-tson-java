package io.ltr8.tson.json;

import io.ltr8.tson.base.io.ByteSink;
import io.ltr8.tson.json.tree.JsonArray;
import io.ltr8.tson.json.tree.JsonNumber;
import io.ltr8.tson.json.tree.JsonObject;
import io.ltr8.tson.json.tree.JsonString;
import io.ltr8.tson.json.tree.JsonValue;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The tree write side, whose whole claim is that it is the exact inverse of the tree read side.
 *
 * <p>That is a stronger claim than {@code TsonTreeWriter} can make, and the reason it is worth pinning:
 * TSON text spells one value several ways and a tree does not record which, where RFC 8259 has six kinds
 * and one spelling each. So the property asserted here is equality of <em>text</em>, not merely of value.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class JsonTreeWriterTest {

    private static final JsonTreeWriter WRITER = new JsonTreeWriter();

    @Nested
    class TheRoundTrip {

        @Test
        void returns_every_shape_as_the_text_it_was_read_from() {
            for (String source : List.of(
                    "{}", "[]", "0", "-1", "\"\"", "\"a\"", "true", "false", "null",
                    "{\"a\":1}", "{\"a\":1,\"b\":2}", "[1,2,3]", "[[],[[]]]",
                    "{\"a\":[1,{\"b\":null}],\"c\":{},\"d\":[]}",
                    "[true,false,null,\"\",0]")) {
                assertEquals(source, WRITER.toJson(Json.parse(source)), source);
            }
        }

        @Test
        void keeps_the_digits_and_the_scale_a_number_was_written_with() {
            // [TSON-JSON] §5.3's promise, and the half of it a read alone cannot check: the tree holds the
            // literal, so trailing zeros and an exponent survive rather than being canonicalised.
            for (String literal : List.of("199.90", "1.0", "0.10", "1e10", "1E+10", "1.000000000000001")) {
                assertEquals(literal, WRITER.toJson(Json.parse(literal)), literal);
            }
            assertEquals(new BigDecimal("199.90"),
                    ((JsonNumber) Json.parse(WRITER.toJson(Json.parse("199.90")))).toBigDecimal());
        }

        @Test
        void keeps_the_content_of_a_string_across_the_escape_boundary() {
            // The lexer's escape decoding and JsonText's quoting are one inverse pair, so what a round trip
            // changes is the spelling and never the content.
            assertEquals("a\nb", Json.parse(WRITER.toJson(new JsonString("a\nb"))).asString());
            assertEquals("\"\\", Json.parse(WRITER.toJson(new JsonString("\"\\"))).asString());
            assertEquals("é☃", Json.parse(WRITER.toJson(new JsonString("é☃"))).asString());
            assertEquals("", Json.parse(WRITER.toJson(new JsonString(""))).asString());
        }

        @Test
        void keeps_a_member_name_that_needs_an_escape() {
            JsonValue value = new JsonObject(Map.of("a\"b", JsonNumber.of(1)));
            assertEquals("{\"a\\\"b\":1}", WRITER.toJson(value));
            assertEquals(value, Json.parse(WRITER.toJson(value)));
        }

        @Test
        void keeps_member_order() {
            assertEquals("{\"z\":1,\"a\":2,\"m\":3}", WRITER.toJson(Json.parse("{\"z\":1,\"a\":2,\"m\":3}")));
        }
    }

    @Nested
    class TheIndentedForm {

        @Test
        void is_the_same_rendering_the_tree_gives_itself() {
            // Two spellings of one rendering, so a caller reaching for either gets the same document --
            // toDisplayString is JEP 540's, this one reaches a sink.
            JsonValue value = Json.parse("{\"a\":[1,{\"b\":null}],\"c\":{},\"d\":[]}");
            assertEquals(value.toDisplayString(), WRITER.indented().toJson(value));
            assertEquals(value.toDisplayString("    "), WRITER.indented("    ").toJson(value));
        }

        @Test
        void reads_back_to_the_same_value() {
            JsonValue value = Json.parse("{\"a\":[1,2],\"b\":{\"c\":\"d\"}}");
            assertEquals(value, Json.parse(WRITER.indented().toJson(value)));
        }

        @Test
        void leaves_an_empty_container_on_one_line() {
            assertEquals("{\n  \"a\": {},\n  \"b\": []\n}",
                    WRITER.indented().toJson(Json.parse("{\"a\":{},\"b\":[]}")));
        }
    }

    @Nested
    class TheSinks {

        private static final JsonValue VALUE = Json.parse("{\"é\":[1,\"☃\"]}");

        @Test
        void a_byte_sink_gets_utf8_and_the_same_bytes_a_string_would_encode_to() {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            WRITER.write(VALUE, bytes);
            assertArrayEquals(WRITER.toJson(VALUE).getBytes(UTF_8), bytes.toByteArray());
        }

        @Test
        void a_buffer_is_a_sink_like_any_other_so_a_document_never_has_to_be_a_string() {
            ByteBuffer buffer = ByteBuffer.allocate(256);
            WRITER.write(VALUE, ByteSink.of(buffer));
            byte[] written = new byte[buffer.position()];
            buffer.flip();
            buffer.get(written);
            assertArrayEquals(WRITER.toJson(VALUE).getBytes(UTF_8), written);
        }

        @Test
        void an_appendable_needs_no_encoding_and_gets_the_same_text() {
            StringBuilder out = new StringBuilder();
            WRITER.write(VALUE, out);
            assertEquals(WRITER.toJson(VALUE), out.toString());
        }

        @Test
        void a_writer_is_derived_not_configured_so_the_original_is_unchanged() {
            JsonTreeWriter indented = WRITER.indented();
            assertEquals("{\"a\":1}", WRITER.toJson(Json.parse("{\"a\":1}")));
            assertEquals("{\n  \"a\": 1\n}", indented.toJson(Json.parse("{\"a\":1}")));
        }
    }

    @Nested
    class HandBuiltValues {

        @Test
        void write_as_readily_as_parsed_ones() {
            Map<String, JsonValue> members = new LinkedHashMap<>();
            members.put("n", JsonNumber.of(new BigDecimal("1.50")));
            members.put("xs", new JsonArray(List.of(JsonString.of("a"))));
            assertEquals("{\"n\":1.50,\"xs\":[\"a\"]}", WRITER.toJson(new JsonObject(members)));
        }
    }
}
