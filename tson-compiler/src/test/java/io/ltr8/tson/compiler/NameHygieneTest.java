package io.ltr8.tson.compiler;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [TSON-DATA] §8.2's restricted-character and restricted-script rules over the two names a Class 1 document
 * carries -- a type-ref name and an annotation name -- as a <b>policy refusal</b> rather than a validity
 * error.
 *
 * <p><b>The classification is the subject.</b> §8.2 keeps these checks out of validity because each reads
 * data the Unicode Consortium declines to freeze, so a verdict can change under a routine UCD refresh while
 * a content-addressed document must mean the same thing forever. A refusal is §8.1's fifth outcome and MUST
 * NOT be reported in any of the four categories -- so a processor that reported a restricted name the way it
 * reports a malformed one would be wrong in the way that matters, however red the document goes either way.
 *
 * <p><b>And exactly once.</b> The check runs where the read context pulls an event, which is also where a
 * lookahead's rewound events come back and where {@code AnnotationCapture} builds a probe context over
 * events already seen. Reporting one name twice is the failure mode that would survive every other test
 * here, so every shape an annotation takes is counted below.
 */
class NameHygieneTest {

    /** U+0132 LATIN CAPITAL LIGATURE IJ: {@code XID_Continue}, and {@code Identifier_Status=Restricted}. */
    private static final String RESTRICTED_NAME = "aĲb";

    private static List<Diagnostic> read(String source) {
        List<Diagnostic> reported = new ArrayList<>();
        new TsonTreeReader().withDiagnostics(reported::add).read(source);
        return reported;
    }

    /** Either per-name rule: one code each, since the two want different fixes. */
    private static boolean isRefusal(Diagnostic diagnostic) {
        return diagnostic.code() == Diagnostic.Code.RESTRICTED_CHARACTER
                || diagnostic.code() == Diagnostic.Code.RESTRICTED_SCRIPT;
    }

    private static long refusals(String source) {
        return read(source).stream().filter(NameHygieneTest::isRefusal).count();
    }

    @Test
    void aRestrictedCharacterInANameIsRefusedAndNotReportedInvalid() {
        List<Diagnostic> reported = read("@" + RESTRICTED_NAME + ":1 2");
        assertEquals(1, reported.size(), reported::toString);
        assertEquals(Diagnostic.Code.RESTRICTED_CHARACTER, reported.get(0).code(),
                "§8.2's refusal is not one of §8.1's four categories, and its code names the rule");
        assertTrue(reported.get(0).message().contains("Identifier_Status=Restricted"), reported::toString);
    }

    /**
     * The grammar's own failures stay validity errors. §7.7 is layer 1 -- {@code XID_*}, NFC and the
     * joining-control contexts, all stable across Unicode versions -- and a name failing it is not a name.
     */
    @Test
    void theGrammarsOwnFailuresAreStillValidityErrors() {
        for (String source : new String[] {"!42x 1", "!x.y 1", "!ab‌cd 1"}) {
            List<Diagnostic> reported = read(source);
            assertTrue(reported.stream().noneMatch(NameHygieneTest::isRefusal),
                    () -> source + " is malformed, not refused: " + reported);
        }
    }

    /** Every shape an annotation takes, each reporting once per occurrence and never once per lookahead. */
    @Test
    void aRefusedNameIsReportedExactlyOnce() {
        String name = RESTRICTED_NAME;
        assertEquals(1, refusals("@" + name + ":1 2"), "root annotation");
        assertEquals(1, refusals("{ x: @" + name + ":1 2 }"), "annotation on a field value");
        assertEquals(1, refusals("@" + name + ":{ y: 1 } 2"), "annotation carrying a record");
        assertEquals(1, refusals("@outer:@" + name + ":1 2"), "annotation nested in an annotation");
        assertEquals(1, refusals("[@" + name + ":1 2]"), "annotation inside an array");
        assertEquals(1, refusals("{ @" + name + ":1 => 2 }"), "annotation on a map key");
        assertEquals(1, refusals("!" + name + " 1"), "type-ref name");
        assertEquals(2, refusals("@" + name + ":1 @" + name + ":2 3"),
                "two occurrences are two refusals -- the rule is per name, not per document");
    }

    // ── The restricted-script rule: the restriction level (§8.2, UTS #39 §5.2) ─────────

    /**
     * A mixed-script name is refused by default. §8.2 makes the restricted-script rule's RECOMMENDED default Highly
     * Restrictive over the whole name, and a homograph reads as another name exactly by mixing scripts
     * inside one word -- Latin {@code p}, Cyrillic {@code а}, Latin {@code y}.
     */
    @Test
    void aMixedScriptNameIsRefusedByDefault() {
        // A type-ref carries a second, unrelated diagnostic in a schemaless read -- no schema is in scope to
        // define any name -- so the refusal is asserted present rather than alone. The annotation case, where
        // nothing else has an opinion, is the one that pins it as the only thing reported.
        for (String source : new String[] {"@pаy:1 2", "!pаy 1"}) {
            assertEquals(1, refusals(source), () -> source + " -> " + read(source));
            assertTrue(read(source).stream().anyMatch(d -> d.message().contains("HIGHLY_RESTRICTIVE")), source);
        }
        assertEquals(1, read("@pаy:1 2").size(), "an annotation name's refusal stands alone");
    }

    /** A single-script name in any script is not mixed and is not this rule's business. */
    @Test
    void aSingleScriptNameIsAdmittedWhateverTheScript() {
        for (String source : new String[] {"@имя:1 2", "@καλή:1 2", "@日本語:1 2", "@pay:1 2"}) {
            assertEquals(List.of(), read(source), source);
        }
    }

    /**
     * <b>Names only.</b> §8.2 defaults the token surface to Unrestricted because a value is data and may
     * legitimately be anything, and Class 1 field names are lexical (§2.5, §7.7) rather than names -- so
     * neither reaches the restricted-script rule, however mixed its scripts.
     */
    @Test
    void aValueAndAClass1FieldNameAreNotNames() {
        assertEquals(List.of(), read("{ a: pаy }"), "a value is data");
        assertEquals(List.of(), read("{ pаy: 1 }"), "a Class 1 field name is lexical, not a name");
    }

    /**
     * §8.2 requires that a deployment be able to relax any of the three rules in code, and names the unit as the
     * relaxation to reach for first: per segment, Highly Restrictive still refuses a within-word homograph
     * while admitting the compounds an author outside Latin script writes.
     */
    @Test
    void thePolicyRelaxesPerSegmentWithoutAdmittingAWithinWordHomograph() {
        TsonTreeReader perSegment = new TsonTreeReader()
                .withNamePolicy(TsonUnicodePolicy.highlyRestrictive().perSegment());
        List<Diagnostic> admitted = new ArrayList<>();
        perSegment.withDiagnostics(admitted::add).read("@url_адрес:1 2");
        assertEquals(List.of(), admitted, "a Latin abbreviation beside a name in another script");

        List<Diagnostic> refused = new ArrayList<>();
        perSegment.withDiagnostics(refused::add).read("@id_pаy:1 2");
        assertEquals(1, refused.size(), "a homograph inside one segment is still refused: " + refused);
    }

    /** And it relaxes away entirely, which a deployment reading untrusted names in many scripts may want. */
    @Test
    void thePolicyRelaxesAway() {
        List<Diagnostic> reported = new ArrayList<>();
        new TsonTreeReader().withNamePolicy(TsonUnicodePolicy.unrestricted())
                .withDiagnostics(reported::add).read("@pаy:1 2");
        assertEquals(List.of(), reported);
    }

    /**
     * <b>And it takes the restricted-character rule with it.</b> §8.2's level 6 "drops the profile too",
     * taking that rule with it -- the one level that does, since every other one keeps {@code
     * Identifier_Status}. A
     * rule that ran regardless of the level would make {@code unrestricted()} a setting that does not
     * do what it says, and would leave a deployment holding a restricted-character refusal it has no way to
     * relax.
     */
    @Test
    void unrestrictedDropsTheIdentifierProfileToo() {
        List<Diagnostic> reported = new ArrayList<>();
        new TsonTreeReader().withNamePolicy(TsonUnicodePolicy.unrestricted())
                .withDiagnostics(reported::add).read("@" + RESTRICTED_NAME + ":1 2");
        assertEquals(List.of(), reported, reported::toString);
    }

    /** An ordinary document pays nothing: no name here is restricted, and none is reported. */
    @Test
    void ordinaryNamesAreUntouched() {
        assertEquals(List.of(), read("@doc:\"x\" !int32 1"));
        assertEquals(List.of(), read("{ id_пользователя: 1 }"),
                "a mixed-script compound field name is not this rule's business");
    }
}
