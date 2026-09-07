package io.ltr8.tson.json;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsCollector;
import io.ltr8.tson.base.ReadException;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a JSON read reports, rather than what it binds: one pass finding every problem, each located by an
 * RFC 6901 pointer and coded from the same closed vocabulary the TSON readers use.
 *
 * <p>The reason it matters is the round trip. A reader that threw could only ever report the first
 * disagreement, so a sender with four mistakes learns them one deploy at a time.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class CollectingJsonReadTest {

    private static final JsonObjectReader READER = JsonObjectReader.standard();

    public record Person(String name, int age) {
    }

    public record Team(String name, List<Person> members) {
    }

    public record Scores(Map<String, Integer> byName) {
    }

    public record Unbindable(Object anything) {
    }

    private static List<Diagnostic> collected(String source, Class<?> type) {
        DiagnosticsCollector problems = new DiagnosticsCollector();
        READER.withDiagnostics(problems).read(source, type);
        return problems.diagnostics();
    }

    private static List<Diagnostic.Code> codes(String source, Class<?> type) {
        return collected(source, type).stream().map(Diagnostic::code).toList();
    }

    @Nested
    class OnePass {

        @Test
        void a_collecting_read_finds_every_problem_where_a_throwing_one_finds_the_first() {
            String source = "{\"name\": 1, \"age\": \"x\", \"extra\": true}";

            assertEquals(List.of(Diagnostic.Code.TYPE_MISMATCH, Diagnostic.Code.TYPE_MISMATCH,
                    Diagnostic.Code.UNRECOGNIZED_FIELD), codes(source, Person.class));

            // The same document through the default reader stops at the first of them.
            assertEquals(Diagnostic.Code.TYPE_MISMATCH,
                    assertThrows(ReadException.class, () -> READER.read(source, Person.class))
                            .diagnostic().code());
        }

        @Test
        void problems_deep_in_a_document_are_all_found() {
            assertEquals(List.of(Diagnostic.Code.TYPE_MISMATCH, Diagnostic.Code.FIELD_REQUIRED),
                    codes("{\"name\": \"Engines\", \"members\": ["
                            + "{\"name\": \"Ada\", \"age\": \"old\"}, {\"name\": \"Grace\"}]}", Team.class));
        }
    }

    @Nested
    class Location {

        @Test
        void each_problem_carries_an_rfc_6901_pointer_to_where_it_is() {
            List<Diagnostic> problems = collected("{\"name\": \"Engines\", \"members\": ["
                    + "{\"name\": \"Ada\", \"age\": 1}, {\"name\": \"Grace\", \"age\": \"old\"}]}", Team.class);
            assertEquals(1, problems.size(), problems::toString);
            assertEquals("/members/1/age", problems.getFirst().path().orElseThrow());
        }

        @Test
        void the_root_pointer_is_the_empty_string_which_is_a_location_and_not_an_absence() {
            assertEquals("", collected("[1, 2]", Person.class).getFirst().path().orElseThrow());
        }

        @Test
        void a_member_name_needing_rfc_6901_escaping_gets_it() {
            // A key holding `/` or `~` would otherwise read as another step of the pointer.
            List<Diagnostic> problems = collected("{\"byName\": {\"a/b\": \"x\", \"c~d\": \"y\"}}", Scores.class);
            assertEquals(List.of("/byName/a~1b", "/byName/c~0d"),
                    problems.stream().map(d -> d.path().orElseThrow()).toList());
        }

        @Test
        void a_problem_carries_the_position_it_was_found_at() {
            Diagnostic only = collected("{\"name\": \"Ada\", \"age\": \"x\"}", Person.class).getFirst();
            assertEquals(1, only.dataPosition().orElseThrow().line());
            // Column 24 is the `"x"` the age member could not take -- the value, not the member name.
            assertEquals(24, only.dataPosition().orElseThrow().column());
        }
    }

    @Nested
    class Verdicts {

        @Test
        void an_ordinary_disagreement_is_a_verdict_on_the_document() {
            assertTrue(collected("{\"name\": \"Ada\", \"age\": \"x\"}", Person.class)
                    .getFirst().code().verdict());
        }

        @Test
        void a_class_this_context_cannot_bind_is_not_a_verdict_on_the_document() {
            // BIND_MISMATCH, not SCHEMA_ERROR: nothing about the document is being asserted, and a caller
            // routing on `verdict()` needs to be able to tell "your JSON is wrong" from "your wiring is".
            List<Diagnostic> problems = collected("{\"anything\": 1}", Unbindable.class);
            assertEquals(List.of(Diagnostic.Code.BIND_MISMATCH),
                    problems.stream().map(Diagnostic::code).toList(), problems::toString);
            assertFalse(problems.getFirst().code().verdict());
        }
    }

    @Nested
    class AllOrNothing {

        @Test
        void a_collecting_read_hands_back_nothing_rather_than_a_half_built_object() {
            // Bind mode is all-or-nothing where tree mode keeps what it built: there is nowhere in a Java
            // record to put a hole, so a record whose members failed is not constructed at all.
            DiagnosticsCollector problems = new DiagnosticsCollector();
            Person bound = READER.withDiagnostics(problems).read("{\"name\": \"Ada\", \"age\": \"x\"}", Person.class);
            assertNull(bound);
            assertFalse(problems.isEmpty());
        }

        @Test
        void a_clean_document_still_binds_and_reports_nothing() {
            DiagnosticsCollector problems = new DiagnosticsCollector();
            assertEquals(new Person("Ada", 36),
                    READER.withDiagnostics(problems).read("{\"name\": \"Ada\", \"age\": 36}", Person.class));
            assertTrue(problems.isEmpty());
        }

        @Test
        void a_derived_reader_leaves_the_original_alone() {
            READER.withDiagnostics(new DiagnosticsCollector());
            assertThrows(ReadException.class, () -> READER.read("{\"age\": \"x\"}", Person.class));
        }
    }
}
