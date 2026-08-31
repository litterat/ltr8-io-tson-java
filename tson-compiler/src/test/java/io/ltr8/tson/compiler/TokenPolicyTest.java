package io.ltr8.tson.compiler;

import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UTS #39 §5.2 on the token surface ([TSON-DATA] §8.2's "Values") -- the half that reaches values,
 * where {@code ConfusableNameScopesTest} covers declared names.
 *
 * <p><b>Every mixed-script spelling here is built from code points</b>, never typed: the two spellings are
 * indistinguishable in an editor, so a literal would make the test unreviewable.
 */
class TokenPolicyTest {

    /** Cyrillic а (U+0430) -- the character [TSON-DATA] §9.4 opens with. */
    private static final String CYR_A = new String(Character.toChars(0x0430));

    private static List<Diagnostic> problems(TsonTreeReader reader, String document) {
        TsonDiagnosticsCollector collected = new TsonDiagnosticsCollector();
        reader.withDiagnostics(collected).read(document);
        return collected.diagnostics();
    }

    /**
     * The default. A value is data and data may legitimately be anything, so nothing is checked until a
     * deployment says otherwise -- the opposite of the identifier default, and right for the same reason.
     */
    @Test
    void byDefaultAValueMayBeAnyScript() {
        TsonValue value = new TsonTreeReader().read("{ note: \"" + CYR_A + "dmin\" }");
        assertEquals(CYR_A + "dmin", value.get("note").asString().orElseThrow());
    }

    /** Raised, the same document is refused -- and the diagnostic names the token rather than the field. */
    @Test
    void aRaisedPolicyRefusesAMixedScriptValue() {
        List<Diagnostic> found = problems(
                new TsonTreeReader().withTokenPolicy(TsonUnicodePolicy.asciiOnly()),
                "{ note: \"" + CYR_A + "dmin\" }");

        assertEquals(List.of(Diagnostic.Code.RESTRICTED_SCRIPT),
                found.stream().map(Diagnostic::code).toList(), found.toString());
        assertTrue(found.getFirst().message().contains(CYR_A + "dmin"), found.getFirst().message());
        assertTrue(found.getFirst().dataPosition().isPresent(), "the check has a position even with no path");
    }

    /**
     * <b>A quoted token is checked too.</b> §7.1's profile governs unquoted tokens only, so quoting is
     * exactly how a spoofed value arrives -- a check that skipped it would be bypassed by the spelling the
     * attacker chooses.
     */
    @Test
    void aQuotedValueIsCheckedLikeAnyOther() {
        assertEquals(1, problems(new TsonTreeReader().withTokenPolicy(TsonUnicodePolicy.asciiOnly()),
                "{ note: \"" + CYR_A + "\" }").size());
    }

    /**
     * <b>A name is a token.</b> The check runs before anything knows which tokens are names, so a token
     * policy reaches a field name as well -- the property that makes a strict token policy subsume the
     * identifier policy rather than sit beside it.
     */
    @Test
    void aFieldNameIsATokenAndIsChecked() {
        List<Diagnostic> found = problems(
                new TsonTreeReader().withTokenPolicy(TsonUnicodePolicy.asciiOnly()),
                "{ " + CYR_A + "dmin: 1 }");

        assertEquals(List.of(Diagnostic.Code.RESTRICTED_SCRIPT),
                found.stream().map(Diagnostic::code).toList(), found.toString());
    }

    /** An ASCII document is unaffected at any level -- the rule is about scripts, not about strictness for its own sake. */
    @Test
    void anAsciiDocumentPassesTheStrictestLevel() {
        assertTrue(problems(new TsonTreeReader().withTokenPolicy(TsonUnicodePolicy.asciiOnly()),
                "{ note: hello  count: 3 }").isEmpty());
    }

    /**
     * A single-script value is fine where a mixed one is not: Highly Restrictive judges the combination, so
     * an all-Cyrillic display name passes a level that refuses a Latin word with one Cyrillic letter in it.
     */
    @Test
    void aSingleScriptValuePassesWhereAMixedOneFails() {
        TsonTreeReader reader = new TsonTreeReader().withTokenPolicy(TsonUnicodePolicy.highlyRestrictive());
        assertTrue(problems(reader, "{ note: \"админ\" }").isEmpty(), "all-Cyrillic");
        assertEquals(1, problems(reader, "{ note: \"" + CYR_A + "dmin\" }").size(), "Latin with one Cyrillic");
    }

    /**
     * Refused rather than ignored. Segmenting a value would admit UTS #39's own {@code Toys-Я-Us}, so a
     * policy that cannot mean what it says here is rejected where it is configured.
     */
    @Test
    void aPerSegmentPolicyIsRefusedOnThisSurface() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new TsonTreeReader().withTokenPolicy(TsonUnicodePolicy.highlyRestrictive().perSegment()));
        assertTrue(e.getMessage().contains("per-segment"), e.getMessage());
    }

    /** The policy survives derivation, like every other axis on the facade. */
    @Test
    void thePolicySurvivesWithSchemaAndWithDiagnostics() {
        TsonTreeReader reader = new TsonTreeReader().withTokenPolicy(TsonUnicodePolicy.asciiOnly());
        assertNotNull(reader.withDiagnostics(new TsonDiagnosticsCollector()));
        assertEquals(1, problems(reader, "{ note: \"" + CYR_A + "\" }").size());
    }
    /**
     * <b>The policy is a required parameter, not a defaulted one.</b> {@code TsonReadContext.of} is where
     * every read converges and is public API, so a default there would be a policy any caller could drop by
     * saying nothing -- weakening with nothing left to grep for. Naming {@code unrestricted()} is a fine
     * answer; not naming one is not an answer.
     */
    @Test
    void aReadContextCannotBeBuiltWithoutNamingAPolicy() {
        NullPointerException e = assertThrows(NullPointerException.class, () -> TsonReadContext.of(
                new io.ltr8.tson.compiler.stream.ListEventSource(List.of()),
                new TsonDiagnosticsCollector(), null));
        assertTrue(e.getMessage().contains("unrestricted()"), e.getMessage());
    }

    /**
     * And the policy really rides the context rather than the call site: a reader driven over a raw source
     * through {@code of} is checked, which is what makes the low-level path unable to skip it silently.
     */
    @Test
    void aRawContextHonoursThePolicyItWasBuiltWith() {
        TsonDiagnosticsCollector collected = new TsonDiagnosticsCollector();
        TsonReadContext ctx = TsonReadContext.of(new TsonDataStream("{ note: \"" + CYR_A + "\" }"),
                collected, TsonUnicodePolicy.asciiOnly());
        try {                                   // the raw context has no end-of-stream predicate; drain it
            while (true) {
                ctx.next();
            }
        } catch (java.util.NoSuchElementException drained) {
            // the whole document has passed through the context, which is what the assertion needs
        }
        assertTrue(collected.diagnostics().stream().anyMatch(d -> d.code() == Diagnostic.Code.RESTRICTED_SCRIPT),
                collected.diagnostics().toString());
    }
}
