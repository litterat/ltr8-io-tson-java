package io.ltr8.tson.json;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link JsonValue} tree and its front door: what a document parses to, how a value navigates and
 * converts, and what it renders back as.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class JsonTest {

    @Nested
    class Parsing {

        @Test
        void each_json_value_kind_reaches_its_own_node_type() {
            assertInstanceOf(JsonObject.class, Json.parse("{}"));
            assertInstanceOf(JsonArray.class, Json.parse("[]"));
            assertInstanceOf(JsonString.class, Json.parse("\"a\""));
            assertInstanceOf(JsonNumber.class, Json.parse("1"));
            assertInstanceOf(JsonBoolean.class, Json.parse("true"));
            assertInstanceOf(JsonNull.class, Json.parse("null"));
        }

        @Test
        void a_nested_document_builds_the_tree_it_describes() {
            JsonValue root = Json.parse("{\"a\": [1, {\"b\": null}], \"c\": true}");
            assertEquals(2, root.asMap().size());
            assertEquals(1, root.get("a").get(0).asInt());
            assertInstanceOf(JsonNull.class, root.get("a").get(1).get("b"));
            assertTrue(root.get("c").asBoolean());
        }

        @Test
        void member_order_is_preserved_although_nothing_reads_it() {
            // §6.1.6 makes order presentation -- "a decoder that requires an order has invented a rule
            // this document does not contain". Keeping it is what lets a document re-emit as it arrived.
            assertEquals(List.of("z", "a", "m"), List.copyOf(Json.parse("{\"z\":1,\"a\":2,\"m\":3}").asMap().keySet()));
        }

        @Test
        void a_duplicate_member_name_is_refused_at_the_repeated_occurrence() {
            // §3.1, and JEP 540 for the same reason: an object whose meaning depends on which member a
            // reader kept is what §10.2 calls the canonical smuggling case.
            JsonParseException e = assertThrows(JsonParseException.class, () -> Json.parse("{\"a\": 1, \"a\": 2}"));
            assertTrue(e.getMessage().contains("'a' is already a member"));
            assertEquals(new JsonPosition(1, 10, 9), e.position());
        }

        @Test
        void a_duplicate_is_judged_by_decoded_name_not_by_spelling() {
            assertThrows(JsonParseException.class, () -> Json.parse("{\"ab\": 1, \"\\u0061b\": 2}"));
        }

        @Test
        void duplicates_in_sibling_objects_are_not_duplicates() {
            assertEquals(2, Json.parse("[{\"a\": 1}, {\"a\": 2}]").asList().size());
        }

        @Test
        void the_stream_is_drained_so_trailing_content_is_refused() {
            assertTrue(assertThrows(JsonParseException.class, () -> Json.parse("[1] 2"))
                    .getMessage().contains("this one is complete"));
        }

        @Test
        void bytes_and_a_string_parse_alike_and_bytes_are_where_the_utf8_rules_bite() {
            String source = "{\"\u00E9\": [1, true, null]}";
            assertEquals(Json.parse(source), Json.parse(new ByteArrayInputStream(source.getBytes(UTF_8))));
            assertTrue(assertThrows(JsonParseException.class,
                    () -> Json.parse(new ByteArrayInputStream(new byte[]{'"', (byte) 0xC3, '"'})))
                    .getMessage().contains("not valid UTF-8"));
        }

        @Test
        void the_nesting_bound_reaches_parse_and_refuses_before_the_reducer_descends() {
            String deep = "[".repeat(200) + "1" + "]".repeat(200);
            assertThrows(JsonLimitExceededException.class, () -> Json.parse(deep));
            assertInstanceOf(JsonArray.class, Json.parse(deep, 256));
        }
    }

    @Nested
    class Navigation {

        private final JsonValue root = Json.parse("{\"a\": {\"b\": [10, 20]}, \"n\": null}");

        @Test
        void get_chains_across_objects_and_arrays_with_no_cast_between_steps() {
            assertEquals(20, root.get("a").get("b").get(1).asInt());
        }

        @Test
        void get_throws_and_names_what_is_actually_there() {
            // Deliberately opposite to TsonValue, whose get/at never throw. Two models for two
            // audiences; the reason to differ is the reason to align -- one method name must not carry
            // opposite semantics on the two sides a consumer moves between.
            assertTrue(assertThrows(JsonValueException.class, () -> root.get("missing"))
                    .getMessage().contains("has no member 'missing'"));
            assertTrue(assertThrows(JsonValueException.class, () -> root.get(0))
                    .getMessage().contains("an object has no elements"));
            assertTrue(assertThrows(JsonValueException.class, () -> root.get("a").get("b").get(5))
                    .getMessage().contains("has 2 elements, so index 5"));
            assertTrue(assertThrows(JsonValueException.class, () -> root.get("n").get("x"))
                    .getMessage().contains("null has no members"));
        }

        @Test
        void try_get_is_the_non_throwing_peer_at_every_shape() {
            assertEquals(Optional.empty(), root.tryGet("missing"));
            assertEquals(Optional.empty(), root.tryGet(0));
            assertEquals(Optional.empty(), root.get("n").tryGet("x"));
            assertEquals(Optional.of(new JsonNumber("10")), root.get("a").get("b").tryGet(0));
        }

        @Test
        void try_value_is_empty_for_null_and_present_for_everything_else() {
            assertEquals(Optional.empty(), root.get("n").tryValue());
            assertEquals(Optional.of(root.get("a")), root.get("a").tryValue());
            assertEquals(Optional.of(JsonBoolean.FALSE), Json.parse("false").tryValue());
        }
    }

    @Nested
    class Conversion {

        @Test
        void each_conversion_answers_only_for_its_own_kind() {
            assertEquals("x", new JsonString("x").asString());
            assertTrue(JsonBoolean.TRUE.asBoolean());
            assertEquals(List.of(), JsonArray.empty().asList());
            assertEquals(Map.of(), JsonObject.empty().asMap());
            assertTrue(assertThrows(JsonValueException.class, () -> new JsonString("1").asInt())
                    .getMessage().contains("this value is a string, not a number"));
            assertThrows(JsonValueException.class, JsonNull.INSTANCE::asString);
            assertThrows(JsonValueException.class, () -> new JsonNumber("1").asList());
        }

        @Test
        void a_narrowing_conversion_errors_rather_than_rounding() {
            // §3.1: "an implementation that cannot represent the digits MUST error, never round
            // silently". Every exact narrowing here is checked, not truncated.
            assertEquals(42, new JsonNumber("42").asInt());
            assertEquals(42L, new JsonNumber("42").asLong());
            assertTrue(assertThrows(JsonValueException.class, () -> new JsonNumber("1.5").asInt())
                    .getMessage().contains("not exactly representable as an int"));
            assertThrows(JsonValueException.class, () -> new JsonNumber("2147483648").asInt());
            assertThrows(JsonValueException.class, () -> new JsonNumber("9223372036854775808").asLong());
            assertEquals(9007199254740993L, new JsonNumber("9007199254740993").asLong());
        }

        @Test
        void as_double_is_the_one_conversion_allowed_to_lose() {
            // Rounding onto the binary grid is the approximate families' own contract (§5.4).
            assertEquals(0.1, new JsonNumber("0.1").asDouble());
            assertEquals(1e300, new JsonNumber("1e300").asDouble());
        }

        @Test
        void full_precision_is_one_call_away() {
            assertEquals(new BigDecimal("1234567890123456789012345.6789"),
                    ((JsonNumber) Json.parse("1234567890123456789012345.6789")).toBigDecimal());
            assertEquals(new java.math.BigInteger("10"), new JsonNumber("1e1").toBigInteger());
            assertThrows(JsonValueException.class, () -> new JsonNumber("1.5").toBigInteger());
        }

        @Test
        void a_number_keeps_its_digits_and_its_scale_through_the_tree() {
            // §5.3: `199.90` is not `199.9`, and the promise is about digits, so digits are what is held.
            assertEquals("199.90", Json.parse("199.90").toString());
            assertEquals("6.02e23", Json.parse("6.02e23").toString());
            assertEquals("-0.0", Json.parse("-0.0").toString());
        }

        @Test
        void number_equality_is_over_the_lexeme_because_a_value_space_needs_a_type() {
            // [TSON-SCHEMA] §5.5 makes equality a property of a value space, and a value space comes
            // from a type -- which this layer has none of. Under a schema these are one `number`; here
            // they are three nodes, and toBigDecimal().compareTo is the comparison a caller means.
            assertNotEquals(Json.parse("1"), Json.parse("1.0"));
            assertNotEquals(Json.parse("100"), Json.parse("1e2"));
            assertEquals(0, new JsonNumber("100").toBigDecimal().compareTo(new JsonNumber("1e2").toBigDecimal()));
        }
    }

    @Nested
    class ValueModel {

        @Test
        void equality_is_over_content_so_two_parses_of_one_document_are_equal() {
            assertEquals(Json.parse("{\"a\": [1, \"x\"]}"), Json.parse("{\"a\":[1,\"x\"]}"));
        }

        @Test
        void object_equality_is_over_the_member_set_and_not_over_the_order() {
            // The Javadoc claims it, and it follows from §6.1.6 making order presentation: two documents
            // differing only in member order carry the same value.
            assertEquals(Json.parse("{\"a\":1,\"b\":2}"), Json.parse("{\"b\":2,\"a\":1}"));
            assertEquals(Json.parse("{\"a\":1,\"b\":2}").hashCode(), Json.parse("{\"b\":2,\"a\":1}").hashCode());
            // ... and the order each was written in survives for re-emission.
            assertEquals("{\"b\":2,\"a\":1}", Json.parse("{\"b\":2,\"a\":1}").toString());
        }

        @Test
        void a_constructed_value_equals_the_parsed_one() {
            assertEquals(Json.parse("{\"a\": [1, true]}"),
                    JsonObject.of(Map.of("a", JsonArray.of(List.of(JsonNumber.of(1), JsonBoolean.of(true))))));
        }

        @Test
        void the_singletons_are_singletons() {
            assertSame(JsonBoolean.TRUE, JsonBoolean.of(true));
            assertSame(JsonNull.INSTANCE, JsonNull.of());
            assertEquals(JsonNull.INSTANCE, Json.parse("null"));
        }

        @Test
        void containers_are_immutable_and_copy_defensively() {
            List<JsonValue> mutable = new java.util.ArrayList<>(List.of(JsonNumber.of(1)));
            JsonArray array = JsonArray.of(mutable);
            mutable.add(JsonNumber.of(2));
            assertEquals(1, array.elements().size());
            assertThrows(UnsupportedOperationException.class, () -> array.elements().add(JsonNumber.of(3)));
            assertThrows(UnsupportedOperationException.class,
                    () -> JsonObject.empty().members().put("a", JsonNull.INSTANCE));
        }
    }

    @Nested
    class Rendering {

        @Test
        void to_string_is_compact_json_that_parses_back_to_the_same_value() {
            // §9.2: an encoder's output MUST be accepted by the decode rules and decode to the value
            // encoded -- the round trip is the conformance test, not a separate rule set.
            for (String source : List.of("{\"a\":[1,true,null,\"x\"],\"b\":{}}", "[]", "{}", "\"\"", "0", "-0.0")) {
                assertEquals(source, Json.parse(source).toString());
                assertEquals(Json.parse(source), Json.parse(Json.parse(source).toString()));
            }
        }

        @Test
        void a_string_is_escaped_the_way_rfc_8259_requires_and_no_further() {
            assertEquals("\"a\\\"b\\\\c\"", new JsonString("a\"b\\c").toString());
            assertEquals("\"\\b\\f\\n\\r\\t\"", new JsonString("\b\f\n\r\t").toString());
            assertEquals("\"\\u0000\\u001f\"", new JsonString("\u0000\u001F").toString());
            // The solidus is legal unescaped and there is no reason to spell it out, and a UTF-8
            // document has no reason to escape an ordinary character.
            assertEquals("\"/ \u00E9 \uD83D\uDE00\"", new JsonString("/ \u00E9 \uD83D\uDE00").toString());
        }

        @Test
        void a_lone_surrogate_in_a_hand_built_value_is_written_as_an_escape_not_raw() {
            // It keeps the output well-formed UTF-8. The value is still one §3.1 refuses on the way back
            // in, and refusing it there is where the spec puts the rule.
            assertEquals("\"\\ud83d\"", new JsonString("\uD83D").toString());
            assertThrows(JsonParseException.class, () -> Json.parse(new JsonString("\uD83D").toString()));
        }

        @Test
        void display_string_is_indented_and_parses_back_to_the_same_value() {
            JsonValue value = Json.parse("{\"a\":[1,2],\"b\":{\"c\":null},\"d\":[],\"e\":{}}");
            String displayed = Json.toDisplayString(value);
            assertEquals("""
                    {
                      "a": [
                        1,
                        2
                      ],
                      "b": {
                        "c": null
                      },
                      "d": [],
                      "e": {}
                    }""", displayed);
            assertEquals(value, Json.parse(displayed));
        }

        @Test
        void the_indent_is_the_callers() {
            assertEquals("[\n\t1\n]", Json.toDisplayString(Json.parse("[1]"), "\t"));
        }

        @Test
        void a_scalar_displays_as_itself() {
            assertEquals("1", Json.toDisplayString(Json.parse("1")));
            assertEquals("null", Json.toDisplayString(JsonNull.INSTANCE));
        }
    }
}
