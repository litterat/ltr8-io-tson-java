package io.ltr8.tson.cli;

import io.ltr8.tson.base.Diagnostic.Code;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link Outcome} answers "will this document be accepted": an invalidity or a refusal rejects it, and only
 * what nobody present could judge leaves it undetermined -- never a rejection.
 */
class OutcomeTest {

    private static Outcome of(Code... codes) {
        return Outcome.of(List.of(codes).stream().map(code -> CliDiagnostic.minimal(code, "m")).toList());
    }

    @Test
    void nothingReportedIsAccepted() {
        assertEquals(Outcome.ACCEPTED, of());
    }

    @Test
    void anInvalidityOrARefusalIsRejected() {
        assertEquals(Outcome.REJECTED, of(Code.TYPE_MISMATCH));
        assertEquals(Outcome.REJECTED, of(Code.RESTRICTED_SCRIPT));
        assertEquals(Outcome.REJECTED, of(Code.CONFUSABLE_NAMES));
        assertEquals(Outcome.REJECTED, of(Code.LIMIT_EXCEEDED));
    }

    @Test
    void whatNobodyPresentCouldJudgeIsUndetermined() {
        assertEquals(Outcome.UNDETERMINED, of(Code.SCHEMA_NOT_FOUND));
        assertEquals(Outcome.UNDETERMINED, of(Code.SCHEMA_TIMEOUT));
        assertEquals(Outcome.UNDETERMINED, of(Code.BIND_MISMATCH));
        assertEquals(Outcome.UNDETERMINED, of(Code.NOT_IMPLEMENTED));
    }

    /** What went unjudged cannot make a rejected document acceptable, so one rejection settles it. */
    @Test
    void oneRejectionSettlesItWhateverElseWentUnjudged() {
        assertEquals(Outcome.REJECTED, of(Code.SCHEMA_NOT_FOUND, Code.TYPE_MISMATCH));
        assertEquals(Outcome.REJECTED, of(Code.NOT_IMPLEMENTED, Code.RESTRICTED_SCRIPT));
    }

    @Test
    void aRunIsRejectedByAnyFileElseUndeterminedByAnyElseAccepted() {
        FileReport accepted = FileReport.of("a.tn", List.of());
        FileReport undetermined = FileReport.of("b.tn", List.of(CliDiagnostic.minimal(Code.SCHEMA_NOT_FOUND, "m")));
        FileReport rejected = FileReport.of("c.tn", List.of(CliDiagnostic.minimal(Code.FIELD_REQUIRED, "m")));

        assertEquals(Outcome.ACCEPTED, Outcome.ofFiles(List.of(accepted, accepted)));
        assertEquals(Outcome.UNDETERMINED, Outcome.ofFiles(List.of(accepted, undetermined)));
        assertEquals(Outcome.REJECTED, Outcome.ofFiles(List.of(undetermined, rejected, accepted)));
    }
}
