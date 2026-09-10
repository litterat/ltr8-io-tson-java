package io.ltr8.tson.json;

import io.ltr8.annotation.Annotations;
import io.ltr8.tson.base.WriteException;
import io.ltr8.tson.base.io.ByteSink;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The object write side: a bound Java object out as JSON, and back through {@link JsonObjectReader} into an
 * equal object.
 *
 * <p><b>Round-tripping through the same class is the whole contract</b>, and stating it that way is not a
 * weaker claim than the tree's text equality -- it is the accurate one. [TSON-JSON] §4.1 makes reading
 * schema-directed and this module's reader lets the class play that part, so a document written here is
 * self-describing only to a reader holding the same class. An {@code int}'s width and a tuple's tuple-ness
 * live in that class, exactly as they live in a schema.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class JsonObjectWriterTest {

    private static final JsonObjectWriter WRITER = JsonObjectWriter.standard();
    private static final JsonObjectReader READER = JsonObjectReader.standard();

    public record Person(String name, int age) {
    }

    public record Team(String name, List<Person> members) {
    }

    public record Scores(Map<String, Integer> byName) {
    }

    public record Diary(Map<LocalDate, Integer> byDate) {
    }

    public record Numbers(int i, long l, double d, BigInteger big, BigDecimal exact) {
    }

    public record Approximate(double d, float f) {
    }

    public record Contents(UUID id, LocalDate on, URI where, byte[] payload) {
    }

    public record Annotated(String name, Annotations annotations) {
    }

    public enum Colour {
        RED, GREEN
    }

    public record Painted(Colour colour, boolean filled) {
    }

    public sealed interface Shape permits Circle, Square {
    }

    public record Circle(int radius) implements Shape {
    }

    public record Square(int side) implements Shape {
    }

    public record Drawing(Shape shape) {
    }

    private static <T> T roundTrip(T value, Class<T> type) {
        return READER.read(WRITER.toJson(value), type);
    }

    @Nested
    class TheRoundTrip {

        @Test
        void returns_an_equal_object_for_a_record() {
            Person ada = new Person("Ada", 36);
            assertEquals("{\"name\":\"Ada\",\"age\":36}", WRITER.toJson(ada));
            assertEquals(ada, roundTrip(ada, Person.class));
        }

        @Test
        void nests_records_and_lists() {
            Team team = new Team("core", List.of(new Person("Ada", 36), new Person("Alan", 41)));
            assertEquals("{\"name\":\"core\",\"members\":"
                    + "[{\"name\":\"Ada\",\"age\":36},{\"name\":\"Alan\",\"age\":41}]}", WRITER.toJson(team));
            assertEquals(team, roundTrip(team, Team.class));
        }

        @Test
        void writes_a_map_as_an_object_keyed_by_its_own_keys() {
            Map<String, Integer> scores = new LinkedHashMap<>();
            scores.put("ada", 1);
            scores.put("alan", 2);
            Scores value = new Scores(scores);
            assertEquals("{\"byName\":{\"ada\":1,\"alan\":2}}", WRITER.toJson(value));
            assertEquals(value, roundTrip(value, Scores.class));
        }

        @Test
        void writes_a_non_string_map_key_through_its_own_atom() {
            // §5.1 hands a member name's content to the key type's own parser, so a LocalDate key writes its
            // date spelling and reads back through the same one.
            Diary diary = new Diary(Map.of(LocalDate.of(2026, 9, 10), 7));
            assertEquals("{\"byDate\":{\"2026-09-10\":7}}", WRITER.toJson(diary));
            assertEquals(diary, roundTrip(diary, Diary.class));
        }

        @Test
        void keeps_every_numeric_family_in_its_own_spelling() {
            Numbers numbers = new Numbers(1, 2L, 0.5, new BigInteger("123456789012345678901234567890"),
                    new BigDecimal("199.90"));
            String written = WRITER.toJson(numbers);
            assertTrue(written.contains("\"big\":123456789012345678901234567890"), written);
            // §5.3's digits and scale, on the write side: not 199.9, and not quoted.
            assertTrue(written.contains("\"exact\":199.90"), written);
            assertEquals(numbers, roundTrip(numbers, Numbers.class));
        }

        @Test
        void writes_an_enum_and_a_boolean_as_themselves() {
            Painted painted = new Painted(Colour.GREEN, true);
            assertEquals("{\"colour\":\"GREEN\",\"filled\":true}", WRITER.toJson(painted));
            assertEquals(painted, roundTrip(painted, Painted.class));
        }

        @Test
        void writes_every_string_content_family_as_a_string() {
            Contents contents = new Contents(UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8"),
                    LocalDate.of(2026, 9, 10), URI.create("https://example.com/a"), new byte[]{1, 2, 3});
            String written = WRITER.toJson(contents);
            assertTrue(written.contains("\"id\":\"6ba7b810-9dad-11d1-80b4-00c04fd430c8\""), written);
            assertTrue(written.contains("\"on\":\"2026-09-10\""), written);
            assertTrue(written.contains("\"where\":\"https://example.com/a\""), written);

            Contents back = roundTrip(contents, Contents.class);
            assertEquals(contents.id(), back.id());
            assertEquals(contents.on(), back.on());
            assertEquals(contents.where(), back.where());
            assertArrayEquals(contents.payload(), back.payload());
        }
    }

    @Nested
    class ValuesJsonHasNoNumberFor {

        @Test
        void are_written_as_the_strings_the_reader_takes_back() {
            // §5.4: the approximate families spell the two infinities and NaN as [TSON-DATA] §7.6's own
            // productions, so the parser that reads them back is the one that reads every other number.
            assertEquals("{\"d\":\".nan\",\"f\":\".inf\"}",
                    WRITER.toJson(new Approximate(Double.NaN, Float.POSITIVE_INFINITY)));
            assertEquals("{\"d\":\"-.inf\",\"f\":\".nan\"}",
                    WRITER.toJson(new Approximate(Double.NEGATIVE_INFINITY, Float.NaN)));
        }

        @Test
        void read_back_to_the_same_values() {
            Approximate value = new Approximate(Double.NEGATIVE_INFINITY, Float.NaN);
            Approximate back = roundTrip(value, Approximate.class);
            assertEquals(Double.NEGATIVE_INFINITY, back.d());
            assertTrue(Float.isNaN(back.f()));
        }

        @Test
        void a_finite_double_stays_an_ordinary_json_number() {
            assertEquals("{\"d\":0.5,\"f\":1.5}", WRITER.toJson(new Approximate(0.5, 1.5f)));
        }
    }

    @Nested
    class WhatIsRefusedRatherThanApproximated {

        @Test
        void a_choice_is_refused_because_no_reader_here_could_dispatch_it() {
            // The exact mirror of the reader's own refusal: §8.2 admits an untagged choice by a declared
            // discriminator or by class-stable disjoint variants, both facts a schema states. Writing one
            // would produce a document this library cannot read back, so it is not written.
            String message = assertThrows(WriteException.class,
                    () -> WRITER.toJson(new Drawing(new Circle(2)))).getMessage();
            assertTrue(message.contains("is a choice"), message);
            assertTrue(message.contains("8.2"), message);
        }

        @Test
        void a_class_with_no_binding_says_so_as_one_unchecked_type() {
            assertThrows(WriteException.class, () -> WRITER.toJson(new Object()));
        }
    }

    @Nested
    class WhatIsDroppedAndWhy {

        @Test
        void an_annotations_carrier_is_not_a_member() {
            // §3.3's annotation object belongs to the schema-directed decode, and this module's reader binds
            // every carrier to Annotations.empty() -- so writing the carrier would emit a member no reader
            // here takes back, which is worse than the symmetric loss.
            assertEquals("{\"name\":\"a\"}", WRITER.toJson(new Annotated("a", Annotations.empty())));
            assertEquals(new Annotated("a", Annotations.empty()),
                    roundTrip(new Annotated("a", Annotations.empty()), Annotated.class));
        }

        @Test
        void an_absent_field_is_left_out_rather_than_written_null() {
            // §7 makes JSON null the absent sentinel at a typed position, so the two say the same thing and
            // the shorter one is what a reader of any strictness takes.
            assertEquals("{\"name\":\"a\"}", WRITER.toJson(new Optional("a", null)));
        }

        public record Optional(String name, String nickname) {
        }
    }

    @Nested
    class TheSinks {

        private static final Person VALUE = new Person("Adéle", 36);

        @Test
        void a_byte_sink_gets_utf8_and_the_same_bytes_a_string_would_encode_to() {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            WRITER.write(VALUE, bytes);
            assertArrayEquals(WRITER.toJson(VALUE).getBytes(UTF_8), bytes.toByteArray());
            assertEquals(VALUE, READER.read(new String(bytes.toByteArray(), UTF_8), Person.class));
        }

        @Test
        void an_appendable_needs_no_encoding_and_gets_the_same_text() {
            StringBuilder out = new StringBuilder();
            WRITER.write(VALUE, out);
            assertEquals(WRITER.toJson(VALUE), out.toString());
        }

        @Test
        void nothing_is_written_at_all_when_a_byte_sink_is_never_flushed() {
            // Closing is not flushing, and the writer flushes for you -- this pins that it is the writer
            // doing it rather than the sink happening to.
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            WRITER.write(VALUE, ByteSink.of(bytes));
            assertTrue(bytes.size() > 0);
        }
    }

    @Nested
    class TheIndentedForm {

        @Test
        void is_a_derivation_leaving_the_original_unchanged() {
            Person ada = new Person("Ada", 36);
            assertEquals("{\n  \"name\": \"Ada\",\n  \"age\": 36\n}", WRITER.indented().toJson(ada));
            assertEquals("{\"name\":\"Ada\",\"age\":36}", WRITER.toJson(ada));
        }

        @Test
        void reads_back_to_the_same_object() {
            Team team = new Team("core", List.of(new Person("Ada", 36)));
            assertEquals(team, READER.read(WRITER.indented().toJson(team), Team.class));
        }
    }

    @Nested
    class TheFrontDoor {

        @Test
        void hands_out_writers_that_are_the_inverses_of_its_readers() {
            Json json = Json.standard();
            Person ada = new Person("Ada", 36);
            assertEquals(ada, json.objectReader().read(json.objectWriter().toJson(ada), Person.class));
            assertEquals(Json.parse("{\"a\":[1,2]}"),
                    json.treeReader().read(json.treeWriter().toJson(Json.parse("{\"a\":[1,2]}"))));
        }
    }
}
