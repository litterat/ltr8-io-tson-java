package io.ltr8.tson.json.stream;

import io.ltr8.tson.base.LimitExceededException;
import io.ltr8.tson.base.LimitsPolicy;
import io.ltr8.tson.base.ParseException;
import io.ltr8.tson.json.JsonPosition;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RFC 8259's structural grammar as an event stream: what each document decomposes into, and what the
 * grammar refuses.
 *
 * <p>Events are compared by a compact rendering rather than by construction, so a test states the
 * shape it expects rather than a column of record literals.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class JsonStreamTest {

    /** Every event of a document, rendered one per entry -- {@code EndOfDocument} included, since pulling it is the point. */
    private static List<String> events(String source) {
        List<String> rendered = new ArrayList<>();
        JsonStream stream = new JsonStream(source);
        while (stream.hasNext()) {
            rendered.add(render(stream.next()));
        }
        return rendered;
    }

    private static String render(JsonEvent event) {
        return switch (event) {
            case JsonEvent.ObjectStart ignored -> "{";
            case JsonEvent.ObjectEnd ignored -> "}";
            case JsonEvent.ArrayStart ignored -> "[";
            case JsonEvent.ArrayEnd ignored -> "]";
            case JsonEvent.MemberName name -> "name(" + name.name() + ")";
            case JsonEvent.StringValue string -> "string(" + string.value() + ")";
            case JsonEvent.NumberValue number -> "number(" + number.literal() + ")";
            case JsonEvent.BooleanValue bool -> "boolean(" + bool.value() + ")";
            case JsonEvent.NullValue ignored -> "null";
            case JsonEvent.EndOfDocument ignored -> "end";
        };
    }

    private static ParseException refused(String source) {
        return assertThrows(ParseException.class, () -> events(source));
    }

    @Nested
    class Shapes {

        @Test
        void a_scalar_document_is_one_value_and_the_end() {
            assertEquals(List.of("number(1)", "end"), events("1"));
            assertEquals(List.of("string(hi)", "end"), events("\"hi\""));
            assertEquals(List.of("boolean(true)", "end"), events("true"));
            assertEquals(List.of("null", "end"), events("null"));
        }

        @Test
        void an_object_is_a_member_name_and_its_value_events_in_order() {
            assertEquals(List.of("{", "name(a)", "number(1)", "name(b)", "string(x)", "}", "end"),
                    events("{\"a\": 1, \"b\": \"x\"}"));
        }

        @Test
        void an_array_is_its_elements_in_order() {
            assertEquals(List.of("[", "number(1)", "boolean(false)", "null", "]", "end"),
                    events("[1, false, null]"));
        }

        @Test
        void the_empty_container_is_its_two_events_and_nothing_between() {
            assertEquals(List.of("{", "}", "end"), events("{}"));
            assertEquals(List.of("[", "]", "end"), events("[]"));
        }

        @Test
        void nesting_is_matched_pairs_and_needs_no_other_state() {
            assertEquals(List.of("[", "{", "name(a)", "[", "number(1)", "]", "}", "[", "]", "]", "end"),
                    events("[{\"a\": [1]}, []]"));
        }

        @Test
        void an_escaped_member_name_and_the_same_name_written_plainly_are_one_name() {
            // §3.1's duplicate rule is about names, not spellings -- which is why this layer decodes the
            // name and the layer that applies the rule compares decoded content.
            assertEquals(events("{\"ab\": 1}"), events("{\"\\u0061b\": 1}"));
        }

        @Test
        void a_number_reaches_the_stream_as_its_exact_lexeme() {
            assertEquals(List.of("[", "number(199.90)", "number(6.02e23)", "number(-0.0)", "]", "end"),
                    events("[199.90, 6.02e23, -0.0]"));
        }

        @Test
        void nothing_here_interprets_null_which_is_a_json_value_at_this_layer() {
            // §7 makes JSON null the absent sentinel's spelling *at a typed position*. This layer has
            // none, so settling it here would impose a schema's answer on a layer that has no schema.
            assertEquals(List.of("{", "name(nickname)", "null", "}", "end"), events("{\"nickname\": null}"));
        }

        @Test
        void a_duplicate_member_name_passes_the_grammar_untouched() {
            // §3.1 makes it an error whose category follows the position's type, which no grammar layer
            // holds. The tree and the schema-directed decode are where it is refused.
            assertEquals(List.of("{", "name(a)", "number(1)", "name(a)", "number(2)", "}", "end"),
                    events("{\"a\": 1, \"a\": 2}"));
        }

        @Test
        void a_reserved_member_name_is_an_ordinary_name_here() {
            // §3.2 reserves `$` where an object is read as a record or an annotation object, which is a
            // question about the position's type.
            assertEquals(List.of("{", "name($type)", "string(cat)", "}", "end"), events("{\"$type\": \"cat\"}"));
        }
    }

    @Nested
    class Grammar {

        @Test
        void an_empty_document_is_not_a_json_text() {
            assertTrue(refused("").getMessage().contains("a JSON document is one value"));
            assertTrue(refused("   ").getMessage().contains("a JSON document is one value"));
        }

        @Test
        void trailing_content_is_refused_because_the_end_is_pulled() {
            // The pull past the root value is what rejects this; a consumer stopping at the root value's
            // last event would read `[1] 2` clean.
            assertTrue(refused("[1] 2").getMessage().contains("this one is complete"));
            assertTrue(refused("{} {}").getMessage().contains("this one is complete"));
            assertTrue(refused("1 2").getMessage().contains("this one is complete"));
            assertTrue(refused("\"a\" \"b\"").getMessage().contains("this one is complete"));
        }

        @Test
        void json_admits_no_trailing_comma_and_says_so() {
            assertTrue(refused("[1,]").getMessage().contains("an element is due"));
            assertTrue(refused("{\"a\": 1,}").getMessage().contains("no trailing comma"));
        }

        @Test
        void a_member_name_must_be_a_quoted_string() {
            assertTrue(refused("{1: 2}").getMessage().contains("a member name is a quoted string"));
            assertTrue(refused("{true: 1}").getMessage().contains("a member name is a quoted string"));
            assertTrue(refused("{[]: 1}").getMessage().contains("a member name is a quoted string"));
        }

        @Test
        void an_unquoted_name_of_letters_is_refused_by_the_lexer_not_by_this_layer() {
            // `{a: 1}` -- the JS object-literal habit, and the commonest of these mistakes -- never
            // reaches the grammar: `a` starts no JSON token, so the lexer refuses it first and names the
            // character rather than the construct. Pinned rather than papered over: the grammar cannot
            // improve on it without the lexer knowing what position it is at, which is the layering.
            assertTrue(refused("{a: 1}").getMessage().contains("'a' is not the start of any JSON value"));
        }

        @Test
        void a_member_needs_its_name_separator() {
            assertTrue(refused("{\"a\" 1}").getMessage().contains("':' separates a member name"));
            assertTrue(refused("{\"a\", 1}").getMessage().contains("':' separates a member name"));
        }

        @Test
        void a_missing_separator_between_members_or_elements_is_refused() {
            assertTrue(refused("[1 2]").getMessage().contains("',' or ']' follows an element"));
            assertTrue(refused("{\"a\": 1 \"b\": 2}").getMessage().contains("',' or '}' follows a member's value"));
        }

        @Test
        void an_unclosed_container_ends_at_the_end_of_the_document() {
            assertTrue(refused("[1").getMessage().contains("',' or ']' follows an element"));
            assertTrue(refused("{\"a\":").getMessage().contains("a member's value is due"));
            assertTrue(refused("{").getMessage().contains("a member name is due"));
            assertTrue(refused("[").getMessage().contains("an element or ']' is due"));
        }

        @Test
        void a_mismatched_closer_is_refused() {
            assertTrue(refused("[1}").getMessage().contains("',' or ']' follows an element"));
            assertTrue(refused("{\"a\": 1]").getMessage().contains("',' or '}' follows a member's value"));
            assertTrue(refused("]").getMessage().contains("a JSON document is one value"));
        }

        @Test
        void a_message_names_the_construct_the_position_admits_not_the_token_class() {
            // "a member name is due" tells an author what to write; "expected STRING" tells them what a
            // lexer calls what they did write.
            String message = refused("{true: 1}").getMessage();
            assertTrue(message.contains("a member name is due"), message);
            assertTrue(message.contains("'true'"), message);
        }

        @Test
        void an_error_reports_at_the_offending_token() {
            assertEquals(new JsonPosition(1, 5, 4), refused("[1, ]").position());
            // The `1` where a ':' was due -- the offending token, not the member name before it.
            assertEquals(new JsonPosition(2, 7, 8), refused("{\n  \"a\" 1}").position());
        }
    }

    @Nested
    class DepthBound {

        private static String nested(int depth) {
            return "[".repeat(depth) + "1" + "]".repeat(depth);
        }

        @Test
        void the_default_bound_is_the_processors_own_not_a_copy_of_it() {
            // §10.1 makes JSON's bound [TSON-DATA] §9.1's policy "in JSON clothing, and the same policy
            // applies with the same defaults" -- so this stream counts against the one default, and a
            // deployment that raises it raises it for both encodings at once.
            assertEquals(64, LimitsPolicy.DEFAULT_MAX_DEPTH);
            assertEquals(64 * 2 + 2, events(nested(LimitsPolicy.DEFAULT_MAX_DEPTH)).size());
        }

        @Test
        void a_document_at_the_bound_reads_and_one_past_it_is_refused() {
            assertEquals(64 * 2 + 2, events(nested(64)).size());
            LimitExceededException e = assertThrows(LimitExceededException.class,
                    () -> events(nested(65)));
            assertEquals(64, e.limit());
            assertTrue(e.getMessage().contains("nests deeper than this processor reads"));
        }

        @Test
        void a_refusal_is_not_a_parse_error_and_is_not_typed_as_one() {
            // §10.1 makes it [TSON-DATA] §8.1's fifth outcome -- never a verdict on the document, so the
            // two must be distinguishable by type or a configured bound reaches a consumer as a syntax
            // failure. Written reflectively because javac refuses the `instanceof`: both are final and
            // unrelated, so the separation is proved at compile time and this only pins it against a
            // later edit that makes one extend the other.
            LimitExceededException e = assertThrows(LimitExceededException.class,
                    () -> events(nested(65)));
            assertFalse(ParseException.class.isAssignableFrom(e.getClass()));
        }

        @Test
        void the_refusal_lands_at_the_container_that_did_not_fit_before_any_consumer_descends() {
            JsonStream stream = new JsonStream("[[[1]]]", LimitsPolicy.defaults().withMaxDepth(2));
            assertInstanceOf(JsonEvent.ArrayStart.class, stream.next());
            assertInstanceOf(JsonEvent.ArrayStart.class, stream.next());
            LimitExceededException e = assertThrows(LimitExceededException.class, stream::next);
            assertEquals(new JsonPosition(1, 3, 2), e.position());
        }

        @Test
        void the_bound_is_configurable_and_objects_count_the_same_as_arrays() {
            assertEquals(List.of("{", "name(a)", "{", "name(b)", "number(1)", "}", "}", "end"),
                    events("{\"a\": {\"b\": 1}}"));
            assertThrows(LimitExceededException.class,
                    () -> new JsonStream("{\"a\": {\"b\": 1}}", LimitsPolicy.defaults().withMaxDepth(1)).forEachRemaining(e -> { }));
        }

        @Test
        void a_bound_below_one_is_a_caller_error_rather_than_a_document_one() {
            assertThrows(IllegalArgumentException.class, () -> new JsonStream("1", LimitsPolicy.defaults().withMaxDepth(0)));
        }

        @Test
        void a_document_deeper_than_the_frame_stack_starts_at_reads_when_the_bound_allows() {
            // The frame array grows on demand rather than being sized from maxDepth, which a caller may
            // set far above any depth a document reaches.
            List<String> rendered = new ArrayList<>();
            new JsonStream(nested(200), LimitsPolicy.defaults().withMaxDepth(256)).forEachRemaining(e -> rendered.add(render(e)));
            assertEquals(200 * 2 + 2, rendered.size());
        }
    }

    @Nested
    class SourceContract {

        @Test
        void peek_does_not_consume_and_repeats() {
            JsonStream stream = new JsonStream("[1]");
            JsonEvent peeked = stream.peek();
            assertSame(peeked, stream.peek());
            assertSame(peeked, stream.next());
            assertInstanceOf(JsonEvent.NumberValue.class, stream.next());
        }

        @Test
        void has_next_stays_true_through_the_end_event_and_false_after_it() {
            JsonStream stream = new JsonStream("1");
            assertTrue(stream.hasNext());
            stream.next();
            assertTrue(stream.hasNext(), "the end event has not been pulled yet");
            assertInstanceOf(JsonEvent.EndOfDocument.class, stream.next());
            assertFalse(stream.hasNext());
        }

        @Test
        void a_peeked_end_event_still_counts_as_pending() {
            JsonStream stream = new JsonStream("1");
            stream.next();
            assertInstanceOf(JsonEvent.EndOfDocument.class, stream.peek());
            assertTrue(stream.hasNext());
            stream.next();
            assertFalse(stream.hasNext());
        }

        @Test
        void pulling_past_the_end_is_a_caller_error() {
            JsonStream stream = new JsonStream("1");
            stream.next();
            stream.next();
            assertThrows(NoSuchElementException.class, stream::next);
        }

        @Test
        void a_document_read_from_bytes_streams_alike() {
            String source = "{\"\u00E9\": [1, true, null]}";
            List<String> fromBytes = new ArrayList<>();
            new JsonStream(new java.io.ByteArrayInputStream(source.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .forEachRemaining(e -> fromBytes.add(render(e)));
            assertEquals(events(source), fromBytes);
        }
    }

    @Nested
    class Positions {

        @Test
        void every_event_carries_the_position_of_the_token_that_produced_it() {
            JsonStream stream = new JsonStream("{\n  \"a\": [1]\n}");
            assertEquals(new JsonPosition(1, 1, 0), stream.next().position());    // {
            assertEquals(new JsonPosition(2, 3, 4), stream.next().position());    // "a"
            assertEquals(new JsonPosition(2, 8, 9), stream.next().position());    // [
            assertEquals(new JsonPosition(2, 9, 10), stream.next().position());   // 1
            assertEquals(new JsonPosition(2, 10, 11), stream.next().position());  // ]
            assertEquals(new JsonPosition(3, 1, 13), stream.next().position());   // }
        }

        @Test
        void a_lexical_failure_still_surfaces_through_the_stream() {
            // The stream pulls tokens lazily, so a bad token mid-document reaches a consumer here rather
            // than at construction.
            JsonStream stream = new JsonStream("[1, +2]");
            stream.next();
            stream.next();
            assertTrue(assertThrows(ParseException.class, stream::next).getMessage().contains("'+'"));
        }
    }
}
