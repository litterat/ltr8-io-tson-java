package io.ltr8.tson;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonDiagnosticsCollector;
import io.ltr8.tson.compiler.TsonUnicodePolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TsonConfig#tokenPolicy} end to end ([TSON-DATA] §8.2's "Values"): the level reaches both
 * facades a {@link Tson} hands out, and its default leaves ordinary data alone.
 *
 * <p>Mixed-script spellings are built from code points rather than typed -- the subject is spellings that
 * look alike, so a literal would be unreviewable.
 */
class TokenPolicyConfigTest {

    /** Cyrillic а (U+0430). */
    private static final String CYR_A = new String(Character.toChars(0x0430));

    /** {@code админ}, all Cyrillic -- a single-script name the default identifier policy admits. */
    private static final String CYRILLIC_NAME = new String(new int[] {0x0430, 0x0434, 0x043C, 0x0438, 0x043D}, 0, 5);

    private static final String DOCUMENT = "{ note: \"" + new String(Character.toChars(0x0430)) + "dmin\" }";

    /**
     * <b>The default checks nothing</b>, which is the whole reason this could ship without changing a single
     * existing read. A value is data, and data may legitimately be any script.
     */
    @Test
    void theDefaultLeavesValuesAlone() {
        assertEquals(List.of(), Tson.builder().build().validate(DOCUMENT));
    }

    /** Raised through the builder, the same document is refused -- so the config really reaches the read. */
    @Test
    void aRaisedPolicyReachesTheTreeReader() {
        List<Diagnostic> found = Tson.builder().tokenPolicy(TsonUnicodePolicy.asciiOnly()).build()
                .validate(DOCUMENT);

        assertEquals(List.of(Diagnostic.Code.RESTRICTED_SCRIPT),
                found.stream().map(Diagnostic::code).toList(), found.toString());
    }

    /**
     * The object facade is wired from the same setting -- one policy, both read modes. Reads a scalar root
     * so the assertion is about the policy and not about resolving a bind target.
     */
    @Test
    void aRaisedPolicyReachesTheObjectReader() {
        TsonDiagnosticsCollector collected = new TsonDiagnosticsCollector();
        Tson.builder().tokenPolicy(TsonUnicodePolicy.asciiOnly()).build()
                .objectReader().withDiagnostics(collected).read("\"" + CYR_A + "dmin\"", String.class);

        assertEquals(List.of(Diagnostic.Code.RESTRICTED_SCRIPT),
                collected.diagnostics().stream().map(Diagnostic::code).toList(),
                collected.diagnostics().toString());
    }

    /**
     * <b>A name is a token, so a strict token policy subsumes the identifier policy.</b> The check runs
     * before anything knows which tokens are names, so a data field name clears it too -- the property the
     * setter is named for.
     *
     * <p>The name is single-script, so only the token policy has anything to say about it: a field name is
     * also judged as a name (§2.5), and this test is about the token surface alone.
     */
    @Test
    void aDataFieldNameIsSubjectToTheTokenPolicy() {
        List<Diagnostic> found = Tson.builder().tokenPolicy(TsonUnicodePolicy.asciiOnly()).build()
                .validate("{ " + CYRILLIC_NAME + ": 1 }");

        assertEquals(List.of(Diagnostic.Code.RESTRICTED_SCRIPT),
                found.stream().map(Diagnostic::code).toList(), found.toString());
    }

    /**
     * And the other half, which needs no configuration at all: a field name is an identifier (§2.5), so it
     * meets the <em>identifier</em> policy the way a type-ref or annotation name does, and that policy
     * defaults to Highly Restrictive. {@code аdmin} spelled with a Cyrillic а is the homograph the rule
     * exists for, and it is refused on a default build reading an ordinary document.
     */
    @Test
    void aDataFieldNameIsAlsoJudgedAsAName() {
        List<Diagnostic> found = Tson.builder().build().validate("{ " + CYR_A + "dmin: 1 }");

        assertEquals(List.of(Diagnostic.Code.RESTRICTED_SCRIPT),
                found.stream().map(Diagnostic::code).toList(), found.toString());
    }

    /** Refused where it is configured, rather than silently ignored one layer down. */
    @Test
    void aPerSegmentTokenPolicyIsRefusedAtConfiguration() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Tson.builder().tokenPolicy(TsonUnicodePolicy.highlyRestrictive().perSegment()));
        assertTrue(e.getMessage().contains("per-segment"), e.getMessage());
    }

}
