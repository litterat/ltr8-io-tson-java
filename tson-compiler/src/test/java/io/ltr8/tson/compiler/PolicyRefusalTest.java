package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.lexer.Xid;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a [TSON-DATA] §8.2 name-hygiene <b>refusal</b> states about itself, as data rather than as prose.
 *
 * <p><b>Which rule refused is the code</b> -- {@code CONFUSABLE_NAMES}, {@code RESTRICTED_CHARACTER},
 * {@code RESTRICTED_SCRIPT}, one each -- because the three want three different fixes: rename one of a colliding
 * pair, change a character outside the identifier profile, or relax the unit or name a script set. A
 * consumer routes on the code, so the rule belongs in it and nowhere else; a second discriminator
 * beside it would restate a fact the code already fixes and would be free to contradict it.
 *
 * <p><b>The data version is the one thing a code cannot carry.</b> §8.2 makes naming it a MUST, and §8.3 is
 * why: it marks all three rules unstable across Unicode releases, so two conforming processors may
 * legitimately disagree about one name and the version is the only thing that explains the disagreement. It
 * is also the only fact about a refusal that is not recoverable from the document plus the schema -- the
 * policy that judged is the reading deployment's own configuration, which whoever set it already holds.
 *
 * <p>Every confusable spelling here is built from code points rather than typed: the subject is spellings
 * that look alike, so a literal would be unreviewable.
 */
class PolicyRefusalTest {

    /** Cyrillic а (U+0430) -- Identifier_Status=Allowed, so only the script rules have anything to say. */
    private static final String CYR_A = new String(Character.toChars(0x0430));

    /** U+0132 LATIN CAPITAL LIGATURE IJ: {@code XID_Continue}, Latin, and Identifier_Status=Restricted. */
    private static final String RESTRICTED_NAME = "a" + new String(Character.toChars(0x0132)) + "b";

    private static List<Diagnostic> read(TsonTreeReader reader, String document) {
        List<Diagnostic> reported = new ArrayList<>();
        reader.withDiagnostics(reported::add).read(document);
        return reported;
    }

    private static Diagnostic soleRefusal(TsonTreeReader reader, String document) {
        List<Diagnostic> reported = read(reader, document);
        assertEquals(1, reported.size(), reported::toString);
        return reported.getFirst();
    }

    /**
     * The look-alike rule over a Class 1 record's own field names -- the one naming scope at the data layer, since
     * no declaration stands behind it. The remedy is to rename one of the pair; there is no policy to relax,
     * the skeleton relation reading a fixed table with nothing configurable in it.
     */
    @Test
    void aConfusableFieldPairIsReportedAsConfusableNames() {
        Diagnostic refusal = soleRefusal(new TsonTreeReader(), "{ admin: 1  " + CYR_A + "dmin: 2 }");

        assertEquals(Diagnostic.Code.CONFUSABLE_NAMES, refusal.code());
    }

    /** A character outside the identifier profile. The remedy is to change the character. */
    @Test
    void aRestrictedCharacterIsItsOwnCode() {
        Diagnostic refusal = soleRefusal(new TsonTreeReader(), "@" + RESTRICTED_NAME + ":1 2");

        assertEquals(Diagnostic.Code.RESTRICTED_CHARACTER, refusal.code());
    }

    /**
     * A script combination the restriction level does not admit. The remedy is a different one -- relax the
     * unit or the level, or name the script set -- which is why this cannot share a code with the test
     * above, however alike the two refusals look.
     */
    @Test
    void aMixedScriptNameIsRestrictedScript() {
        Diagnostic refusal = soleRefusal(new TsonTreeReader(), "@p" + CYR_A + "y:1 2");

        assertEquals(Diagnostic.Code.RESTRICTED_SCRIPT, refusal.code());
    }

    /**
     * <b>One read, two surfaces.</b> The identifier policy and the token policy are separate settings, so a
     * single document can be refused twice -- once for the name and once for the token it is written with --
     * and each refusal is located where its own surface saw it: the name at a path, the token at a position
     * before any reader had descended to one.
     */
    @Test
    void oneReadCanRefuseAtBothSurfaces() {
        List<Diagnostic> reported = read(
                new TsonTreeReader().withTokenPolicy(TsonUnicodePolicy.asciiOnly()),
                "@p" + CYR_A + "y:1 2");

        assertEquals(List.of(Diagnostic.Code.RESTRICTED_SCRIPT, Diagnostic.Code.RESTRICTED_SCRIPT),
                reported.stream().map(Diagnostic::code).toList(), reported::toString);
        assertTrue(reported.stream().anyMatch(d -> d.path().isEmpty()),
                () -> "the token surface reports before any path exists: " + reported);
    }

    /**
     * <b>A value is refused under the restricted-script rule alone.</b> A token is not a name -- it has no
     * identifier profile and no scope to be distinct within -- so {@code RESTRICTED_SCRIPT} is the only one
     * of the three codes a value surface can produce.
     */
    @Test
    void aRefusedValueIsRestrictedScript() {
        Diagnostic refusal = soleRefusal(
                new TsonTreeReader().withTokenPolicy(TsonUnicodePolicy.asciiOnly()),
                "{ note: \"p" + CYR_A + "y\" }");

        assertEquals(Diagnostic.Code.RESTRICTED_SCRIPT, refusal.code());
    }

    /**
     * <b>And nothing has to be mixed for it to fire</b>, which is why the code names the script the policy
     * refused rather than what the text did. At {@code ASCII_ONLY} a single-script name is refused outright:
     * UTS #39 §5.2's first level admits no script but Latin-ASCII, so the check never reaches a comparison
     * between two scripts. A code spelled for mixing would be false here.
     */
    @Test
    void aSingleScriptValueIsRefusedWhereTheLevelAdmitsNoSuchScript() {
        Diagnostic refusal = soleRefusal(
                new TsonTreeReader().withTokenPolicy(TsonUnicodePolicy.asciiOnly()),
                "{ note: \"" + CYR_A + "\" }");

        assertEquals(Diagnostic.Code.RESTRICTED_SCRIPT, refusal.code());
        assertTrue(refusal.message().contains("not ASCII"), refusal::message);
    }

    /**
     * <b>A refusal carries nothing an ordinary verdict does not.</b> What tells the two apart is the code,
     * which is what a consumer routes on; the [TSON-DATA] §8.2 data version and the level that judged are
     * facts about this processor, so they are read off the reader rather than off the problem, and stay one
     * statement however many names a document gets refused for.
     */
    @Test
    void aRefusalIsShapedLikeAnyOtherDiagnostic() {
        TsonTreeReader reader = new TsonTreeReader().withTokenPolicy(TsonUnicodePolicy.asciiOnly());
        Diagnostic refusal = soleRefusal(reader, "{ note: \"" + CYR_A + "\" }");
        Diagnostic verdict = read(new TsonTreeReader(), "{ a: 1  a: 2 }").getFirst();

        assertEquals(Diagnostic.Code.RESTRICTED_SCRIPT, refusal.code());
        assertEquals(Diagnostic.Code.DUPLICATE_FIELD, verdict.code());
        assertEquals(refusal.fetchReason(), verdict.fetchReason(),
                "a refusal has no component of its own to distinguish it -- the code is the distinction");
    }

    /**
     * <b>The policy is reachable without a refusal in hand, off the reader that would do the refusing.</b>
     * That is what makes a §8.2 divergence explainable at all: the level, the unit and the data version are
     * this deployment's own configuration, in neither the document nor the schema, and constant for the life
     * of a process -- so they are stated once beside a run's diagnostics, and available before a document is
     * written at all. Read off the reader rather than a config object because a derived reader is exactly
     * where the two can differ.
     *
     * <p>The version is unreachable any other way: {@code io.ltr8.tson.compiler.lexer} is implementation and
     * is not exported, so a consumer cannot read {@code Xid.UNICODE_VERSION} by hand.
     */
    @Test
    void theProcessorPolicyIsReachableWithoutARefusal() {
        TsonUnicodeProcessorPolicy policy = new TsonTreeReader()
                .withTokenPolicy(TsonUnicodePolicy.asciiOnly())
                .withNamePolicy(TsonUnicodePolicy.singleScript().perSegment())
                .processorPolicy();

        assertEquals(TsonUnicodePolicy.Level.SINGLE_SCRIPT, policy.identifierPolicy().level());
        assertTrue(policy.identifierPolicy().isPerSegment());
        assertEquals(TsonUnicodePolicy.Level.ASCII_ONLY, policy.tokenPolicy().level());
        assertEquals(Xid.UNICODE_VERSION, policy.unicodeDataVersion());
        assertEquals(Xid.UNICODE_VERSION, TsonUnicodePolicy.dataVersion());
    }
}
