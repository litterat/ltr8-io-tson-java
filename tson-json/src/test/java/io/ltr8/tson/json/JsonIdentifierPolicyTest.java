package io.ltr8.tson.json;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsCollector;
import io.ltr8.tson.base.ReadException;
import io.ltr8.tson.base.policy.ProcessorPolicy;
import io.ltr8.tson.base.policy.UnicodePolicy;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [TSON-DATA] §8.2's identifier policy, reaching JSON member names -- and reaching exactly the ones that
 * are names.
 *
 * <p><b>The position decides, and only one reader here holds one.</b> §4.1 makes a {@code {...}} a record
 * or a map by the position it stands at, and JSON spells both the same way, so nothing that merely reads a
 * member name can say whether it read a name or a key. {@link JsonObjectReader} can: the target class
 * stands in for the schema, so a record component's members are field names and a {@code Map} component's
 * are keys. {@link JsonTreeReader} cannot and applies nothing -- which is not a gap to be closed but the
 * same fact that made this a separate stack from {@code tson-compiler}, whose text grammar tells the two
 * apart syntactically.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class JsonIdentifierPolicyTest {

    /** Cyrillic а (U+0430) in an otherwise Latin name -- the mixed-script case §8.2's level refuses. */
    private static final String MIXED_SCRIPT = "{\"n\\u0430me\": \"x\", \"age\": 1}";

    /** U+00AD SOFT HYPHEN: inside the grammar's continue set, outside the identifier profile. */
    private static final String RESTRICTED_CHARACTER = "{\"na\\u00ADme\": \"x\", \"age\": 1}";

    private static final JsonObjectReader READER = JsonObjectReader.standard();

    public record Person(String name, int age) {
    }

    public record Scores(Map<String, Integer> byName) {
    }

    private static List<Diagnostic.Code> codes(JsonObjectReader reader, String source, Class<?> type) {
        DiagnosticsCollector problems = new DiagnosticsCollector();
        reader.withDiagnostics(problems).read(source, type);
        return problems.diagnostics().stream().map(Diagnostic::code).toList();
    }

    /** §8.2's three rules, one code each -- what this policy can report and nothing else. */
    private static final Set<Diagnostic.Code> HYGIENE = Set.of(Diagnostic.Code.CONFUSABLE_NAMES,
            Diagnostic.Code.RESTRICTED_CHARACTER, Diagnostic.Code.RESTRICTED_SCRIPT);

    /**
     * Just the §8.2 refusals.
     *
     * <p>A name this policy refuses is, by construction, not one the class declares -- so the same read
     * also reports the ordinary problems that follow (an undeclared member, a required one missing). Those
     * are a different question, and filtering here is what keeps each test asserting its own.
     */
    private static List<Diagnostic.Code> refusals(JsonObjectReader reader, String source, Class<?> type) {
        return codes(reader, source, type).stream().filter(HYGIENE::contains).toList();
    }

    @Nested
    class AtARecordPosition {

        @Test
        void a_mixed_script_member_name_is_refused() {
            assertTrue(codes(READER, MIXED_SCRIPT, Person.class).contains(Diagnostic.Code.RESTRICTED_SCRIPT),
                    "Highly Restrictive whole-name is §8.2's SHOULD and this library's default");
        }

        @Test
        void a_member_name_outside_the_identifier_profile_is_refused() {
            assertTrue(codes(READER, RESTRICTED_CHARACTER, Person.class)
                    .contains(Diagnostic.Code.RESTRICTED_CHARACTER));
        }

        @Test
        void an_ordinary_name_is_not() {
            assertEquals(List.of(), codes(READER, "{\"name\": \"x\", \"age\": 1}", Person.class));
        }

        @Test
        void a_refusal_is_a_verdict_but_not_an_invalidity() {
            // §8.2 says a refusal MUST NOT be reported in any of §8.1's four categories, and §9.4 carries
            // those categories into this encoding unchanged -- so the *code* is what keeps it apart, one
            // per rule. It stays a verdict: the processor looked and declined, and the sender holds the
            // fix, which is the question a consumer routes on.
            DiagnosticsCollector problems = new DiagnosticsCollector();
            READER.withDiagnostics(problems).read(MIXED_SCRIPT, Person.class);
            Diagnostic refusal = problems.diagnostics().stream()
                    .filter(d -> HYGIENE.contains(d.code())).findFirst().orElseThrow();
            assertEquals(Diagnostic.Code.RESTRICTED_SCRIPT, refusal.code());
            assertTrue(refusal.code().verdict(), refusal.toString());
            assertEquals("'n\u0430me'", refusal.actual());
        }

        @Test
        void a_fail_fast_read_throws_it_like_any_other_problem() {
            ReadException thrown = assertThrows(ReadException.class, () -> READER.read(MIXED_SCRIPT, Person.class));
            assertEquals(Diagnostic.Code.RESTRICTED_SCRIPT, thrown.diagnostic().code());
        }

        @Test
        void a_name_the_class_does_not_declare_is_judged_too() {
            // §8.2's scope is the names the document wrote. A name refused for its characters is refused
            // whether or not the class was going to keep the value under it -- and this is the case that
            // matters most, a look-alike of a declared name arriving as an undeclared one.
            List<Diagnostic.Code> codes = codes(READER, "{\"name\": \"x\", \"age\": 1, \"\\u0430ge\": 2}",
                    Person.class);
            assertTrue(codes.contains(Diagnostic.Code.RESTRICTED_SCRIPT), codes.toString());
            assertTrue(codes.contains(Diagnostic.Code.UNRECOGNIZED_FIELD), codes.toString());
        }
    }

    @Nested
    class AtAMapPosition {

        @Test
        void a_key_is_data_and_meets_no_name_rule() {
            // The whole reason the rule needed a position: the same bytes at a Map component are keys, and
            // a key that mixes scripts is an ordinary value. This is also what keeps a JSON-Schema
            // conversion from meeting a name rule on `additionalProperties`.
            assertEquals(List.of(), codes(READER, "{\"byName\": {\"n\\u0430me\": 1}}", Scores.class));
        }

        @Test
        void even_one_outside_the_identifier_profile() {
            assertEquals(List.of(), codes(READER, "{\"byName\": {\"na\\u00ADme\": 1}}", Scores.class));
        }
    }

    @Nested
    class TheTreeReader {

        @Test
        void applies_nothing_because_it_holds_no_position() {
            // Not a gap: a JsonObject's members could be either, and guessing is what §4.1 forbids.
            DiagnosticsCollector problems = new DiagnosticsCollector();
            JsonTreeReader.standard().withDiagnostics(problems).read(MIXED_SCRIPT);
            assertTrue(problems.isEmpty(), problems.diagnostics().toString());
        }
    }

    @Nested
    class ThePolicyIsConfigured {

        @Test
        void relaxing_it_admits_what_the_default_refuses() {
            // §8.2 requires a relaxation be code rather than ambient, which is what this surface is.
            JsonObjectReader relaxed = READER.withProcessorPolicy(ProcessorPolicy.defaults()
                    .withIdentifierPolicy(UnicodePolicy.unrestricted()));
            assertEquals(List.of(), refusals(relaxed, MIXED_SCRIPT, Person.class));
        }

        @Test
        void unrestricted_drops_the_identifier_profile_with_the_level() {
            JsonObjectReader relaxed = READER.withProcessorPolicy(ProcessorPolicy.defaults()
                    .withIdentifierPolicy(UnicodePolicy.unrestricted()));
            assertEquals(List.of(), refusals(relaxed, RESTRICTED_CHARACTER, Person.class));
        }

        @Test
        void naming_the_scripts_a_deployment_expects_admits_them() {
            JsonObjectReader cyrillic = READER.withProcessorPolicy(ProcessorPolicy.defaults()
                    .withIdentifierPolicy(UnicodePolicy.highlyRestrictive()
                            .permitting(Character.UnicodeScript.LATIN, Character.UnicodeScript.CYRILLIC)));
            assertEquals(List.of(), refusals(cyrillic, MIXED_SCRIPT, Person.class));
        }

        @Test
        void a_derived_reader_leaves_the_original_judging_as_it_did() {
            READER.withProcessorPolicy(ProcessorPolicy.defaults()
                    .withIdentifierPolicy(UnicodePolicy.unrestricted()));
            assertTrue(codes(READER, MIXED_SCRIPT, Person.class).contains(Diagnostic.Code.RESTRICTED_SCRIPT));
        }
    }

    @Nested
    class BesideTheTokenPolicy {

        @Test
        void the_token_policy_reaches_the_key_this_one_cannot() {
            // The two surfaces judge different things and report the same code: a script the policy does
            // not admit is RESTRICTED_SCRIPT whichever policy said so, because the remedy is the same. What
            // separates them is what they are asked of -- names here, every token there -- so the map key
            // the identifier policy leaves alone is refused the moment a deployment restricts tokens.
            String key = "{\"byName\": {\"n\\u0430me\": 1}}";
            assertEquals(List.of(), codes(READER, key, Scores.class));

            JsonObjectReader strictTokens = READER.withProcessorPolicy(ProcessorPolicy.defaults()
                    .withTokenPolicy(UnicodePolicy.singleScript()));
            assertTrue(codes(strictTokens, key, Scores.class).contains(Diagnostic.Code.RESTRICTED_SCRIPT),
                    "a key is out of the identifier policy's reach and inside the token policy's");
        }
    }
}
