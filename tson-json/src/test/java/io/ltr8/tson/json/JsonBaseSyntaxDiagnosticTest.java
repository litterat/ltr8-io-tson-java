package io.ltr8.tson.json;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsCollector;
import io.ltr8.tson.base.ReadException;
import io.ltr8.tson.base.policy.LimitsPolicy;
import io.ltr8.tson.base.policy.ProcessorPolicy;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A document that will not parse, reported through the read's own receiver rather than thrown past it --
 * the JSON half of what {@code TsonDiagnostics.ofBaseSyntaxError} does for TSON text.
 *
 * <p>The property under test is that <b>the receiver decides the failure's fate</b>, syntax included. A
 * collecting read of a malformed document returns nothing and says why through the collector; a fail-fast
 * one throws, and throws the same {@link ReadException} a value-level problem does, because for a
 * fail-fast read the receiver is what throws. Without that, a caller who asked for every problem in one
 * pass gets an exception for the one failure they cannot see coming, and the two encodings disagree about
 * a rule [TSON-DATA] §8.1 states once.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class JsonBaseSyntaxDiagnosticTest {

    public record Person(String name, int age) {
    }

    @Nested
    class ACollectingRead {

        @Test
        void reports_a_malformed_document_rather_than_throwing_past_the_collector() {
            DiagnosticsCollector problems = new DiagnosticsCollector();

            Object tree = JsonTreeReader.standard().withDiagnostics(problems).read("{\"name\": }");

            assertNull(tree, "a document that does not parse yields no tree");
            assertEquals(List.of(Diagnostic.Code.VALIDATION_ERROR),
                    problems.diagnostics().stream().map(Diagnostic::code).toList());
        }

        @Test
        void reports_it_on_the_object_path_too() {
            DiagnosticsCollector problems = new DiagnosticsCollector();

            Person person = JsonObjectReader.standard().withDiagnostics(problems)
                    .read("{\"name\": \"ada\", \"age\": }", Person.class);

            assertNull(person);
            assertEquals(List.of(Diagnostic.Code.VALIDATION_ERROR),
                    problems.diagnostics().stream().map(Diagnostic::code).toList());
        }

        @Test
        void locates_the_failure_and_names_the_encoding_that_refused() {
            DiagnosticsCollector problems = new DiagnosticsCollector();
            JsonTreeReader.standard().withDiagnostics(problems).read("[1] 2");

            Diagnostic d = problems.diagnostics().getFirst();
            assertTrue(d.dataPosition().isPresent(), d.toString());
            assertTrue(d.dataPosition().orElseThrow().byteOffset() > 0, d.toString());
            assertEquals("well-formed JSON", d.expected());
            assertTrue(d.code().verdict(), "a syntax error is a verdict the sender can act on");
        }

        @Test
        void keeps_the_value_level_problems_it_found_before_the_syntax_failed() {
            DiagnosticsCollector problems = new DiagnosticsCollector();

            JsonObjectReader.standard().withDiagnostics(problems)
                    .read("{\"name\": 1, \"age\": \"x\", ", Person.class);

            List<Diagnostic.Code> codes = problems.diagnostics().stream().map(Diagnostic::code).toList();
            assertTrue(codes.size() > 1, "the earlier problems survive the trip: " + codes);
            assertEquals(Diagnostic.Code.VALIDATION_ERROR, codes.getLast());
        }
    }

    @Nested
    class AFailFastRead {

        @Test
        void throws_ReadException_the_way_a_value_level_problem_does() {
            ReadException thrown = assertThrows(ReadException.class,
                    () -> JsonTreeReader.standard().read("{\"name\": }"));

            assertEquals(Diagnostic.Code.VALIDATION_ERROR, thrown.diagnostic().code());
            assertTrue(thrown.diagnostic().dataPosition().isPresent());
        }

        @Test
        void carries_the_position_into_a_stack_trace_without_repeating_it_in_the_message() {
            ReadException thrown = assertThrows(ReadException.class,
                    () -> JsonObjectReader.standard().read("{\"name\": }", Person.class));

            assertFalse(thrown.getMessage().contains("at line"), thrown.getMessage());
            assertTrue(thrown.toString().contains("at line"), thrown.toString());
        }
    }

    @Nested
    class ALimitRefusal {

        private static final ProcessorPolicy SHALLOW =
                ProcessorPolicy.defaults().withLimits(LimitsPolicy.defaults().withMaxDepth(3));

        @Test
        void is_classified_apart_from_a_syntax_error_and_is_not_a_verdict() {
            DiagnosticsCollector problems = new DiagnosticsCollector();

            JsonTreeReader.standard().withProcessorPolicy(SHALLOW).withDiagnostics(problems)
                    .read("[[[[1]]]]");

            Diagnostic d = problems.diagnostics().getFirst();
            assertEquals(Diagnostic.Code.LIMIT_EXCEEDED, d.code());
            assertFalse(d.code().verdict(), "§9.1 is a statement about this processor, not about the document");
        }

        @Test
        void still_throws_for_a_fail_fast_read() {
            assertThrows(ReadException.class,
                    () -> JsonTreeReader.standard().withProcessorPolicy(SHALLOW).read("[[[[1]]]]"));
        }
    }

    @Nested
    class WhatIsNotClassified {

        @Test
        void a_fault_in_this_library_reaches_the_caller_as_itself() {
            // Not a base-syntax failure and not a limit: a bug is not a verdict on the document, so it
            // must not arrive as one.
            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> JsonTreeReader.standard().withDiagnostics(new DiagnosticsCollector())
                            .read(new FaultyEvents()));
            assertEquals("a fault, not a document problem", thrown.getMessage());
        }
    }

    /** An event source that fails the way this library would if it were broken, rather than the document. */
    private static final class FaultyEvents implements io.ltr8.tson.json.stream.JsonEventSource {
        @Override
        public io.ltr8.tson.json.stream.JsonEvent peek() {
            throw new IllegalStateException("a fault, not a document problem");
        }

        @Override
        public io.ltr8.tson.json.stream.JsonEvent next() {
            throw new IllegalStateException("a fault, not a document problem");
        }

        @Override
        public boolean hasNext() {
            return true;
        }
    }
}
