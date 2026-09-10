package io.ltr8.tson.json;

import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsCollector;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.policy.LimitsPolicy;
import io.ltr8.tson.base.policy.ProcessorPolicy;
import io.ltr8.tson.base.ReadException;
import io.ltr8.tson.base.policy.UnicodePolicy;
import io.ltr8.tson.json.tree.JsonValue;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Json} as a front door: one place a deployment states its policy, its binding and where problems
 * go, and two readers that carry it -- the shape {@code Tson} takes over its own two.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class JsonFrontDoorTest {

    public record Person(String name, int age) {
    }

    private static final String CYRILLIC_A = "\u0430";

    @Test
    void both_readers_carry_the_configuration_the_front_door_holds() {
        DiagnosticsCollector problems = new DiagnosticsCollector();
        Json json = Json.of(ProcessorConfig.defaults()
                .withProcessorPolicy(ProcessorPolicy.defaults().withTokenPolicy(UnicodePolicy.asciiOnly())));

        json.treeReader().withDiagnostics(problems).read("{\"note\": \"" + CYRILLIC_A + "\"}");
        json.objectReader().withDiagnostics(problems)
                .read("{\"name\": \"" + CYRILLIC_A + "\", \"age\": 1}", Person.class);

        assertEquals(2, problems.diagnostics().size(), problems.diagnostics()::toString);
        assertTrue(problems.diagnostics().stream()
                .allMatch(d -> d.code() == Diagnostic.Code.RESTRICTED_SCRIPT), problems.diagnostics()::toString);
    }

    /**
     * The configuration is a value and the front door is built from one, so deriving a different
     * configuration cannot reach an instance already built from another.
     */
    @Test
    void a_front_door_built_from_one_configuration_is_untouched_by_another() {
        ProcessorConfig config = ProcessorConfig.defaults();
        Json json = Json.of(config);

        config.withProcessorPolicy(ProcessorPolicy.defaults().withTokenPolicy(UnicodePolicy.asciiOnly()));

        assertEquals(UnicodePolicy.unrestricted().level(), json.processorPolicy().tokenPolicy().level());
    }

    @Test
    void the_bound_is_stated_once_and_both_readers_carry_it() {
        Json json = Json.of(ProcessorConfig.defaults().withProcessorPolicy(
                ProcessorPolicy.defaults().withLimits(LimitsPolicy.defaults().withMaxDepth(3))));

        // Both readers report the one policy the front door holds, which is the point of it holding one.
        assertEquals(3, json.treeReader().processorPolicy().limits().maxDepth());
        assertEquals(3, json.objectReader().processorPolicy().limits().maxDepth());

        // And it is applied, not merely carried. A refusal reaches a fail-fast caller the way every other
        // problem does -- through the receiver, as ReadException -- and says LIMIT_EXCEEDED, which is not a
        // verdict: the document is fine, this processor declined to read it.
        io.ltr8.tson.base.ReadException refused = assertThrows(io.ltr8.tson.base.ReadException.class,
                () -> json.treeReader().read("[".repeat(10) + "1" + "]".repeat(10)));
        assertEquals(Diagnostic.Code.LIMIT_EXCEEDED, refused.diagnostic().code());
    }

    @Test
    void the_two_readers_differ_on_what_a_collecting_read_hands_back() {
        // The asymmetry CLAUDE.md calls deliberate: a JsonObject has somewhere to put a partial answer and
        // a Java record does not, so a tree keeps what it built where a bound read hands back nothing.
        DiagnosticsCollector treeProblems = new DiagnosticsCollector();
        JsonValue tree = Json.standard().treeReader().withDiagnostics(treeProblems)
                .read("{\"a\": 1, \"a\": 2}");
        assertFalse(treeProblems.isEmpty());
        assertEquals(2, tree.get("a").asInt());

        DiagnosticsCollector boundProblems = new DiagnosticsCollector();
        Person bound = Json.standard().objectReader().withDiagnostics(boundProblems)
                .read("{\"name\": \"Ada\", \"age\": \"x\"}", Person.class);
        assertFalse(boundProblems.isEmpty());
        assertNull(bound);
    }

    @Test
    void the_binding_the_front_door_holds_reaches_the_object_reader() {
        DataBindContext context = DataBindContext.builder().build();
        assertEquals(new Person("Ada", 36),
                Json.of(ProcessorConfig.defaults().withDataBindContext(context)).objectReader().read("{\"name\": \"Ada\", \"age\": 36}", Person.class));
    }

    @Test
    void jep_540s_statics_still_answer_over_the_defaults() {
        assertEquals(1, Json.parse("[1]").get(0).asInt());
        assertEquals("[\n  1\n]", Json.toDisplayString(Json.parse("[1]")));
        assertThrows(ReadException.class, () -> Json.parse("{\"a\": 1, \"a\": 2}"));
    }
}
