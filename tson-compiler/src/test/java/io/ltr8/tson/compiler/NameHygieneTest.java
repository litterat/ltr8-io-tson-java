package io.ltr8.tson.compiler;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [TSON-DATA] §8.2's mechanism 2 over the two names a Class 1 document carries -- a type-ref name and an
 * annotation name -- as a <b>policy refusal</b> rather than a validity error.
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

    private static long refusals(String source) {
        return read(source).stream().filter(d -> d.code() == Diagnostic.Code.RESTRICTED_TOKEN).count();
    }

    @Test
    void aRestrictedCharacterInANameIsRefusedAndNotReportedInvalid() {
        List<Diagnostic> reported = read("@" + RESTRICTED_NAME + ":1 2");
        assertEquals(1, reported.size(), reported::toString);
        assertEquals(Diagnostic.Code.RESTRICTED_TOKEN, reported.get(0).code(),
                "§8.2's refusal is not one of §8.1's four categories");
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
            assertTrue(reported.stream().noneMatch(d -> d.code() == Diagnostic.Code.RESTRICTED_TOKEN),
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

    /** An ordinary document pays nothing: no name here is restricted, and none is reported. */
    @Test
    void ordinaryNamesAreUntouched() {
        assertEquals(List.of(), read("@doc:\"x\" !int32 1"));
        assertEquals(List.of(), read("{ id_пользователя: 1 }"),
                "a mixed-script compound field name is not this mechanism's business");
    }
}
