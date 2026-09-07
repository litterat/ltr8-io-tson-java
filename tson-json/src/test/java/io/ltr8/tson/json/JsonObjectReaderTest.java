package io.ltr8.tson.json;

import io.ltr8.tson.base.ParseException;
import io.ltr8.tson.base.LimitExceededException;
import io.ltr8.annotation.Annotations;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsCollector;
import io.ltr8.tson.base.ReadException;
import io.ltr8.annotation.Field;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading a JSON document into a Java object, with the target class standing in for the schema.
 *
 * <p>What is asserted here is "does this document fit this class", which is what the reader checks. It
 * is not validation against a TSON schema, and the rules that need one -- facets, field states,
 * defaults, §8's dispatch -- are deliberately absent and are tested where they arrive.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class JsonObjectReaderTest {

    private static final JsonObjectReader READER = JsonObjectReader.standard();

    // A primitive component is required (tson-bind makes it so); an object component is not, unless the
    // class says so with @Field(required = true). Both states are exercised below.
    public record Person(String name, int age) {
    }

    public record Team(String name, List<Person> members) {
    }

    public record Scores(Map<String, Integer> byName) {
    }

    public record Numbers(int i, long l, double d, BigInteger big, BigDecimal exact) {
    }

    public record Required(@Field(required = true) String name) {
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

    /** The one problem a fail-fast read raises, as a diagnostic -- what {@code throwing()} wraps. */
    private static Diagnostic refused(String source, Class<?> type) {
        return assertThrows(ReadException.class, () -> READER.read(source, type)).diagnostic();
    }

    /** Every problem a collecting read finds, in one pass. */
    private static List<Diagnostic> collected(String source, Class<?> type) {
        DiagnosticsCollector problems = new DiagnosticsCollector();
        READER.withDiagnostics(problems).read(source, type);
        return problems.diagnostics();
    }

    @Nested
    class Records {

        @Test
        void a_flat_object_binds_member_by_member() {
            assertEquals(new Person("Ada", 36), READER.read("{\"name\": \"Ada\", \"age\": 36}", Person.class));
        }

        @Test
        void member_order_does_not_matter() {
            assertEquals(new Person("Ada", 36), READER.read("{\"age\": 36, \"name\": \"Ada\"}", Person.class));
        }

        @Test
        void the_class_decides_the_brace_which_is_the_whole_point() {
            // §4.1 reads a value at a typed position and never by inspecting it. The same `{"a": 1}` is a
            // record here and a map at Scores -- the question JSON's syntax cannot answer, answered by the
            // position, and the one a shared TSON event vocabulary could not express.
            assertEquals(new Scores(Map.of("a", 1)), READER.read("{\"byName\": {\"a\": 1}}", Scores.class));
            assertEquals(new Person("a", 1), READER.read("{\"name\": \"a\", \"age\": 1}", Person.class));
        }

        @Test
        void nesting_and_collections_bind_through() {
            assertEquals(new Team("Engines", List.of(new Person("Ada", 36), new Person("Grace", 45))),
                    READER.read("{\"name\": \"Engines\", \"members\": ["
                            + "{\"name\": \"Ada\", \"age\": 36}, {\"name\": \"Grace\", \"age\": 45}]}", Team.class));
        }

        @Test
        void a_member_the_class_does_not_declare_refuses_the_document() {
            // Not tidiness: a member added in a later version can change what the members this class does
            // read mean -- a currency beside an amount, a unit beside a quantity. A reader that drops it
            // has not read a subset of the document, it has read a different document and cannot tell.
            Diagnostic e = refused("{\"name\": \"Ada\", \"currency\": \"AUD\", \"age\": 36}", Person.class);
            assertTrue(e.message().contains("Person declares no member 'currency'"), e.message());
            assertTrue(e.message().contains("(name, age)"), e.message());
            assertEquals(new JsonPosition(1, 17, 16), e.dataPosition().orElseThrow());
        }

        @Test
        void the_derived_reader_discards_one_instead_and_the_default_reader_is_unchanged() {
            // The opt-out is the derived reader, never the default: the safe reading is the one nobody has
            // to know to ask for.
            JsonObjectReader lenient = READER.ignoringUnknownMembers();
            assertEquals(new Person("Ada", 36), lenient.read(
                    "{\"name\": \"Ada\", \"extra\": {\"deep\": [1, {\"x\": null}]}, \"age\": 36}", Person.class));
            assertThrows(ReadException.class, () -> READER.read(
                    "{\"name\": \"Ada\", \"extra\": 1, \"age\": 36}", Person.class));
        }

        @Test
        void a_missing_required_member_is_refused_and_a_missing_optional_one_is_not() {
            // A primitive component is required; an object component is not unless the class says so.
            assertEquals(new Person(null, 36), READER.read("{\"age\": 36}", Person.class));
            assertTrue(refused("{\"name\": \"Ada\"}", Person.class).message()
                    .contains("requires a member 'age'"));
            assertTrue(refused("{}", Required.class).message().contains("requires a member 'name'"));
        }

        @Test
        void a_duplicate_member_name_is_refused_at_the_repeat() {
            Diagnostic e = refused("{\"name\": \"a\", \"name\": \"b\", \"age\": 1}", Person.class);
            assertTrue(e.message().contains("'name' is already a member"));
            assertEquals(new JsonPosition(1, 15, 14), e.dataPosition().orElseThrow());
        }

        @Test
        void a_value_of_the_wrong_shape_names_what_was_due_and_what_arrived() {
            assertTrue(refused("[1, 2]", Person.class).message()
                    .contains("Person takes an object, and this is an array"));
            assertTrue(refused("{\"name\": \"a\", \"age\": {}}", Person.class).message()
                    .contains("an object cannot be read into int"));
        }

        @Test
        void an_annotations_carrier_is_filled_empty_because_json_carries_none() {
            // §4.3: no annotation channel exists on this wire, so there is nothing to capture and nothing
            // dropped.
            Annotated bound = READER.read("{\"name\": \"Ada\"}", Annotated.class);
            assertEquals("Ada", bound.name());
            assertEquals(Annotations.empty(), bound.annotations());
        }
    }

    @Nested
    class NullAndAbsence {

        @Test
        void null_at_an_optional_member_is_the_absence_and_binds_null() {
            // §7: JSON null is the absent sentinel's spelling, admitted where the position admits absence.
            assertEquals(new Person(null, 36), READER.read("{\"name\": null, \"age\": 36}", Person.class));
        }

        @Test
        void omitted_and_null_are_indistinguishable_in_the_result() {
            // §6.1.2 says so outright, and a bound object has no third state to tell them apart -- the
            // asymmetry TsonAbsent exists for on the tree side.
            assertEquals(READER.read("{\"age\": 1}", Person.class),
                    READER.read("{\"name\": null, \"age\": 1}", Person.class));
        }

        @Test
        void null_at_a_required_member_is_refused_as_the_absent_sentinel_is_in_text() {
            // [TSON-SCHEMA] §7.6: `_` at a REQUIRED field is an error, and §7 makes null its JSON spelling.
            assertTrue(refused("{\"name\": \"a\", \"age\": null}", Person.class).message()
                    .contains("member 'age' is required and is written null"));
            assertTrue(refused("{\"name\": null}", Required.class).message()
                    .contains("member 'name' is required and is written null"));
        }

        @Test
        void null_inside_a_collection_is_the_element_it_stands_at() {
            assertEquals(List.of(), READER.read("{\"name\": \"x\", \"members\": []}", Team.class).members());
            Team withNull = READER.read("{\"name\": \"x\", \"members\": [null]}", Team.class);
            assertEquals(1, withNull.members().size());
            assertNull(withNull.members().getFirst());
        }
    }

    @Nested
    class Atoms {

        @Test
        void each_numeric_target_reads_the_same_json_number_its_own_way() {
            // §4.1: the target decides, never the JSON kind.
            Numbers bound = READER.read(
                    "{\"i\": 1, \"l\": 9007199254740993, \"d\": 0.1, \"big\": 42, \"exact\": 199.90}",
                    Numbers.class);
            assertEquals(1, bound.i());
            assertEquals(9007199254740993L, bound.l());
            assertEquals(0.1, bound.d());
            assertEquals(BigInteger.valueOf(42), bound.big());
            // §5.3: digits and scale survive, so this is 199.90 and not 199.9.
            assertEquals(new BigDecimal("199.90"), bound.exact());
            assertEquals("199.90", bound.exact().toPlainString());
        }

        @Test
        void a_narrowing_that_would_lose_is_an_error_rather_than_a_round() {
            // §3.1: "an implementation that cannot represent the digits MUST error, never round silently".
            assertTrue(refused("{\"name\": \"a\", \"age\": 1.5}", Person.class).message()
                    .contains("1.5 is not exactly representable as int"));
            assertTrue(refused("{\"name\": \"a\", \"age\": 2147483648}", Person.class).message()
                    .contains("not exactly representable as int"));
        }

        @Test
        void an_approximate_target_is_the_one_allowed_to_lose() {
            // Rounding onto the binary grid is the approximate families' own contract (§5.4).
            assertEquals(0.1, READER.read(
                    "{\"i\":0,\"l\":0,\"d\":0.1000000000000000055511151231257827,\"big\":0,\"exact\":0}",
                    Numbers.class).d());
        }

        @Test
        void a_string_reads_an_enum_by_its_constant_name() {
            assertEquals(new Painted(Colour.GREEN, true), READER.read("{\"colour\": \"GREEN\", \"filled\": true}",
                    Painted.class));
            // The lookup is EnumStringBridge's, not this reader's: tson-bind binds every plain Java enum
            // through it, so an enum component arrives as a bridged String atom.
            assertTrue(refused("{\"colour\": \"BLUE\", \"filled\": true}", Painted.class).message()
                    .contains("'BLUE' is not a Colour"));
        }

        @Test
        void a_boolean_target_takes_a_boolean_and_not_its_spelling() {
            assertTrue(refused("{\"colour\": \"RED\", \"filled\": \"true\"}", Painted.class).message()
                    .contains("cannot be read into boolean"));
        }
    }

    @Nested
    class Maps {

        @Test
        void an_object_at_a_map_position_reads_its_member_names_as_keys() {
            assertEquals(new Scores(Map.of("a", 1, "b", 2)),
                    READER.read("{\"byName\": {\"a\": 1, \"b\": 2}}", Scores.class));
        }

        @Test
        void a_key_that_is_no_identifier_is_an_ordinary_key() {
            // §3.2: map keys are data, and this reader reserves nothing -- which is what a rest-shaped
            // position will need when the schema-directed decode arrives.
            assertEquals(Map.of("first name", 1, "$type", 2),
                    READER.read("{\"byName\": {\"first name\": 1, \"$type\": 2}}", Scores.class).byName());
        }

        @Test
        void a_repeated_key_is_refused() {
            assertTrue(refused("{\"byName\": {\"a\": 1, \"a\": 2}}", Scores.class).message()
                    .contains("'a' is already a key of this map"));
        }
    }

    @Nested
    class Unions {

        @Test
        void a_union_target_is_refused_and_says_why_rather_than_guessing() {
            // §8.2 admits a tag-free choice by exactly two routes -- a declared discriminator, or a derived
            // disjointness fact -- and forbids extending them. A Java union states neither, and trying each
            // member in turn is what §8.2 rules out in as many words.
            String message = refused("{\"shape\": {\"radius\": 1}}", Drawing.class).message();
            assertTrue(message.contains("is a union"), message);
            assertTrue(message.contains("no selector"), message);
            assertTrue(message.contains("§8.2"), message);
        }
    }

    @Nested
    class DocumentContract {

        @Test
        void trailing_content_is_refused_because_the_source_is_drained() {
            assertTrue(assertThrows(ParseException.class,
                    () -> READER.read("{\"name\": \"a\", \"age\": 1} 2", Person.class))
                    .getMessage().contains("this one is complete"));
        }

        @Test
        void bytes_and_a_string_read_alike() {
            String source = "{\"name\": \"Adéle\", \"age\": 1}";
            assertEquals(READER.read(source, Person.class),
                    READER.read(new ByteArrayInputStream(source.getBytes(UTF_8)), Person.class));
        }

        @Test
        void the_nesting_bound_holds_even_over_a_value_being_discarded() {
            // Read through the lenient reader on purpose: the deep value is one nothing keeps, which is
            // exactly where a bound is easiest to lose -- skipValue walks it and must still be counted.
            JsonObjectReader lenient = READER.ignoringUnknownMembers();
            String deep = "{\"name\": \"a\", \"age\": 1, \"extra\": " + "[".repeat(200) + "]".repeat(200) + "}";
            assertThrows(LimitExceededException.class, () -> lenient.read(deep, Person.class));
            assertInstanceOf(Person.class, lenient.read(deep, Person.class, 256));
        }

        @Test
        void a_document_that_is_not_json_fails_as_a_parse_rather_than_a_bind() {
            // The two exceptions answer different questions: "this is not JSON" against "this is not my
            // JSON", and a caller routes on which it caught.
            assertThrows(ParseException.class, () -> READER.read("{\"name\": }", Person.class));
        }
    }
}
