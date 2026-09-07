package io.ltr8.tson.json;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsCollector;
import io.ltr8.tson.base.policy.ProcessorPolicy;
import io.ltr8.tson.base.policy.UnicodePolicy;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [TSON-DATA] §8.2's token surface on the JSON side, reached into this encoding by [TSON-JSON] §9.4:
 * "the token policy, when a deployment sets one, reaches map keys and string values".
 *
 * <p>It rides the stream rather than the read context, because a stream produces each token exactly once
 * where a context rewinds -- the same reason, and now the same mechanism, as the TSON side.
 *
 * <p>The Cyrillic character below is U+0430, written as an escape because a literal one is
 * indistinguishable from the ASCII letter it impersonates.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class JsonTokenPolicyTest {

    private static final String CYRILLIC_A = "\u0430";

    public record Note(String text) {
    }

    public record Scores(Map<String, Integer> byName) {
    }

    private static List<Diagnostic> under(UnicodePolicy policy, String source, Class<?> type) {
        DiagnosticsCollector problems = new DiagnosticsCollector();
        JsonObjectReader.standard()
                .withProcessorPolicy(ProcessorPolicy.defaults().withTokenPolicy(policy))
                .withDiagnostics(problems).read(source, type);
        return problems.diagnostics();
    }

    @Test
    void a_string_value_whose_scripts_the_policy_refuses_is_refused() {
        List<Diagnostic> problems = under(UnicodePolicy.asciiOnly(),
                "{\"text\": \"p" + CYRILLIC_A + "ssword\"}", Note.class);
        assertEquals(List.of(Diagnostic.Code.RESTRICTED_SCRIPT),
                problems.stream().map(Diagnostic::code).toList(), problems::toString);
    }

    @Test
    void the_default_scans_nothing_because_a_value_may_legitimately_be_anything() {
        // §8.2 leaves the token surface unrestricted until a deployment says otherwise, and requires that
        // saying so be code rather than ambient -- which is what withTokenPolicy is.
        assertEquals(List.of(), under(UnicodePolicy.unrestricted(),
                "{\"text\": \"p" + CYRILLIC_A + "ssword\"}", Note.class));
        assertEquals(new Note("p" + CYRILLIC_A + "ssword"),
                JsonObjectReader.standard().read("{\"text\": \"p" + CYRILLIC_A + "ssword\"}", Note.class));
    }

    @Test
    void a_map_key_is_a_token_and_is_reached_by_this_policy() {
        // §9.4 names map keys explicitly. They are data, not names, so the *identifier* policy leaves them
        // alone -- this is the surface that does reach them.
        List<Diagnostic> problems = under(UnicodePolicy.asciiOnly(),
                "{\"byName\": {\"" + CYRILLIC_A + "\": 1}}", Scores.class);
        assertTrue(problems.stream().anyMatch(d -> d.code() == Diagnostic.Code.RESTRICTED_SCRIPT),
                problems::toString);
    }

    @Test
    void a_refusal_is_reported_once_even_though_the_stream_is_peeked_across() {
        // The check runs as an event leaves the stream, so an event held back by peek() is checked when it
        // is finally taken -- not once per peek that crossed it. That is the property a decorator used to
        // buy on the TSON side and the reason neither encoding puts this in the read context.
        List<Diagnostic> problems = under(UnicodePolicy.asciiOnly(),
                "{\"text\": \"" + CYRILLIC_A + "\"}", Note.class);
        assertEquals(1, problems.size(), problems::toString);
    }

    @Test
    void a_derived_reader_leaves_the_original_alone() {
        JsonObjectReader strict = JsonObjectReader.standard()
                .withProcessorPolicy(ProcessorPolicy.defaults().withTokenPolicy(UnicodePolicy.asciiOnly()));
        assertEquals(new Note(CYRILLIC_A),
                JsonObjectReader.standard().read("{\"text\": \"" + CYRILLIC_A + "\"}", Note.class));
        DiagnosticsCollector problems = new DiagnosticsCollector();
        strict.withDiagnostics(problems).read("{\"text\": \"" + CYRILLIC_A + "\"}", Note.class);
        assertTrue(!problems.isEmpty());
    }
}
