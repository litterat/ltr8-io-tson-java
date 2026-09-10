package io.ltr8.tson.json;

import io.ltr8.tson.base.ReadException;
import io.ltr8.tson.base.DiagnosticsCollector;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.policy.LimitsPolicy;
import io.ltr8.tson.base.policy.ProcessorPolicy;
import io.ltr8.tson.base.ParseException;
import io.ltr8.tson.json.tree.JsonArray;
import io.ltr8.tson.json.tree.JsonBoolean;
import io.ltr8.tson.json.tree.JsonNull;
import io.ltr8.tson.json.tree.JsonNumber;
import io.ltr8.tson.json.tree.JsonObject;
import io.ltr8.tson.json.tree.JsonString;
import io.ltr8.tson.json.tree.JsonValue;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The front door: what a document parses to, and what a value renders back as. How a parsed value then
 * navigates and converts is {@code JsonValueTest}'s.
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
            //
            // A DUPLICATE_FIELD diagnostic rather than a ParseException, because the grammar accepts the
            // document -- §3.1 puts a repeat in the categories that follow the position's type, not in the
            // parse category. JEP 540 calls it a parse error for want of anywhere else to put it; this has
            // somewhere, and the default receiver still throws at the first.
            Diagnostic d = assertThrows(ReadException.class,
                    () -> Json.parse("{\"a\": 1, \"a\": 2}")).diagnostic();
            assertEquals(Diagnostic.Code.DUPLICATE_FIELD, d.code());
            assertTrue(d.message().contains("'a' is already a member"));
            assertEquals("/a", d.path().orElseThrow());
        }

        @Test
        void a_collecting_tree_read_finds_every_duplicate_and_still_hands_back_the_tree() {
            // The reason the rule moved off ParseException. Tree mode keeps what it built, where a bound
            // read hands back nothing: a JsonObject has somewhere to put a partial answer.
            DiagnosticsCollector problems = new DiagnosticsCollector();
            JsonValue tree = JsonTreeReader.standard().withDiagnostics(problems)
                    .read("{\"a\": 1, \"a\": 2, \"b\": 3, \"b\": 4}");
            assertEquals(List.of(Diagnostic.Code.DUPLICATE_FIELD, Diagnostic.Code.DUPLICATE_FIELD),
                    problems.diagnostics().stream().map(Diagnostic::code).toList());
            assertEquals(List.of("/a", "/b"),
                    problems.diagnostics().stream().map(d -> d.path().orElseThrow()).toList());
            // Last value wins, which is what a reader that carries on has to do with the earlier one.
            assertEquals(2, tree.get("a").asInt());
            assertEquals(4, tree.get("b").asInt());
        }

        @Test
        void a_duplicate_is_judged_by_decoded_name_not_by_spelling() {
            assertThrows(ReadException.class, () -> Json.parse("{\"ab\": 1, \"\\u0061b\": 2}"));
        }

        @Test
        void duplicates_in_sibling_objects_are_not_duplicates() {
            assertEquals(2, Json.parse("[{\"a\": 1}, {\"a\": 2}]").asList().size());
        }

        @Test
        void the_stream_is_drained_so_trailing_content_is_refused() {
            assertTrue(assertThrows(ReadException.class, () -> Json.parse("[1] 2"))
                    .getMessage().contains("this one is complete"));
        }

        @Test
        void bytes_and_a_string_parse_alike_and_bytes_are_where_the_utf8_rules_bite() {
            String source = "{\"\u00E9\": [1, true, null]}";
            assertEquals(Json.parse(source), Json.parse(new ByteArrayInputStream(source.getBytes(UTF_8))));
            assertTrue(assertThrows(ReadException.class,
                    () -> Json.parse(new ByteArrayInputStream(new byte[]{'"', (byte) 0xC3, '"'})))
                    .getMessage().contains("not valid UTF-8"));
        }

        @Test
        void the_nesting_bound_reaches_parse_and_refuses_before_the_reducer_descends() {
            String deep = "[".repeat(200) + "1" + "]".repeat(200);
            assertThrows(ReadException.class, () -> Json.parse(deep));
            assertInstanceOf(JsonArray.class, Json.parse(deep,
                    ProcessorPolicy.defaults().withLimits(LimitsPolicy.defaults().withMaxDepth(256))));
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
            assertThrows(ReadException.class, () -> Json.parse(new JsonString("\uD83D").toString()));
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
