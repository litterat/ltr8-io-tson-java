package io.ltr8.tson.compiler;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsCollector;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [TSON-DATA] §8.2: a name this processor refused was never read, so nothing downstream holds a verdict
 * about it.
 *
 * <p>Reporting a refused name as unrecognised claims to have looked it up, which the processor declined to
 * do -- and it misinstructs the sender, telling them to add a field that is already declared when the fix
 * is one character. The refusal itself is unchanged and still reported: it is not one of §8.1's four
 * categories ({@code Code.verdict()} is false), which is what §8.2 requires of it.
 *
 * <p>This covers the class-driven reader ({@code SchemalessObjectReader}). The schema-driven one
 * ({@code RecordAbstractReader}) applies the same rule and is tested at the front door, in {@code :tson},
 * where a schema can actually be resolved -- a document's verdict must not depend on which reader read it.
 *
 * <p>The homoglyph below is {@code password} with U+0430 CYRILLIC SMALL LETTER A in place of the ASCII
 * {@code a} -- the case the look-alike rules exist for, written with an escape because a literal one is an
 * editing hazard.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RefusedNameDrawsNoVerdictTest {

    private static final String HOMOGLYPH = "p\u0430ssword";

    /** §8.2's three rules, one code each -- the codes that mean "refused", never "invalid". */
    private static final List<Diagnostic.Code> REFUSALS = List.of(Diagnostic.Code.CONFUSABLE_NAMES,
            Diagnostic.Code.RESTRICTED_CHARACTER, Diagnostic.Code.RESTRICTED_SCRIPT);

    public record Account(String password) {
    }

    public record Profile(String nickname) {
    }

    private static List<Diagnostic.Code> codes(String document, Class<?> target) {
        DiagnosticsCollector collected = new DiagnosticsCollector();
        new TsonObjectReader().withDiagnostics(collected).read(document, target);
        return collected.diagnostics().stream().map(Diagnostic::code).toList();
    }

    @Test
    void a_refused_name_draws_the_refusal_and_nothing_else() {
        assertEquals(List.of(Diagnostic.Code.RESTRICTED_SCRIPT),
                codes("{ " + HOMOGLYPH + ": \"x\" }", Account.class));
    }

    @Test
    void the_refusal_is_still_reported_and_nothing_claims_the_document_is_invalid() {
        // §8.2's own requirement and the half that must not change -- the shape `checkRefusedVector` asserts
        // of the conformance corpus: something was refused, and nothing was reported as invalid.
        //
        // A refusal *is* a verdict (`Code.verdict()` is true): the processor looked and declined, and the
        // sender holds the fix. That is exactly why a second, contradictory instruction beside it is worse
        // than a redundant one -- both tell the sender what to do, and they disagree.
        DiagnosticsCollector collected = new DiagnosticsCollector();
        new TsonObjectReader().withDiagnostics(collected).read("{ " + HOMOGLYPH + ": \"x\" }", Account.class);
        assertEquals(1, collected.diagnostics().size(), collected.diagnostics()::toString);
        Diagnostic only = collected.diagnostics().getFirst();
        assertTrue(REFUSALS.contains(only.code()), only::toString);
        assertTrue(only.message().contains("mixes the scripts"), only::message);
    }

    @Test
    void a_refused_name_that_is_also_genuinely_unknown_still_draws_only_the_refusal() {
        // Profile declares no `password` at all, so this name is both refused and unmatched. The sender
        // fixes the character first and learns on the next round whether the field exists -- which is the
        // deliberate consequence of a refused name not having been read.
        assertEquals(List.of(Diagnostic.Code.RESTRICTED_SCRIPT),
                codes("{ " + HOMOGLYPH + ": \"x\" }", Profile.class));
    }

    @Test
    void an_ordinary_unknown_name_still_draws_its_verdict() {
        assertEquals(List.of(Diagnostic.Code.UNRECOGNIZED_FIELD),
                codes("{ nickname: \"x\" }", Account.class));
    }

    @Test
    void a_refused_name_beside_a_good_one_costs_the_good_one_nothing() {
        assertEquals(List.of(Diagnostic.Code.RESTRICTED_SCRIPT),
                codes("{ password: \"x\"  " + HOMOGLYPH + ": \"y\" }", Account.class));
    }
}
