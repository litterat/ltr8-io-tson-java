package io.ltr8.tson.cli;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonReadContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutputFormatTest {

    @Test
    void parseAcceptsTheThreeKnownFormatsCaseInsensitively() {
        assertEquals(OutputFormat.TEXT, OutputFormat.parse("text"));
        assertEquals(OutputFormat.JSON, OutputFormat.parse("JSON"));
        assertEquals(OutputFormat.TSON, OutputFormat.parse("Tson"));
    }

    @Test
    void parseRejectsAnUnknownFormat() {
        assertThrows(UsageException.class, () -> OutputFormat.parse("yaml"));
    }

    @Test
    void textRendersOkForAValidReport() {
        assertEquals("OK", OutputFormat.TEXT.render(ValidationReport.ok()));
    }

    @Test
    void textRendersTheCodeAndMessageForAFailedReport() {
        String rendered = OutputFormat.TEXT.render(ValidationReport.failed(Diagnostic.Code.VALIDATION_ERROR, "boom"));
        assertEquals("[VALIDATION_ERROR] boom", rendered);
    }

    @Test
    void textIncludesThePathWhenPresent() {
        CliDiagnostic diagnostic = new CliDiagnostic("/value", "", "", Diagnostic.Code.FIELD_REQUIRED, "missing", "a value",
                "(absent)", Optional.empty(), Optional.empty());
        String rendered = OutputFormat.TEXT.render(new ValidationReport(false, List.of(diagnostic)));
        assertEquals("[FIELD_REQUIRED] /value: missing", rendered);
    }

    /**
     * [TSON-DATA] §8.1 requires source position in *all* error reports, and a pointer on its own does not
     * tell a human which line to open. The JSON output has carried both ends all along; this is the text
     * renderer catching up.
     */
    @Test
    void textIncludesThePositionAlongsideThePath() {
        CliDiagnostic diagnostic = new CliDiagnostic("/address/city", "", "", Diagnostic.Code.TYPE_MISMATCH,
                "expected text", "text", "42", Optional.of("3:12:47"), Optional.empty());

        assertEquals("[TYPE_MISMATCH] /address/city (3:12:47): expected text",
                OutputFormat.TEXT.render(new ValidationReport(false, List.of(diagnostic))));
    }

    /**
     * A base-syntax error has no path into a document that would not parse, but it does know where it gave
     * up -- and the byte offset appears nowhere else, the exception's own message carrying line and column
     * only. Printing the position with no pointer is what keeps every diagnostic locatable by the same rule.
     */
    @Test
    void textIncludesAPositionThatHasNoPointerToHangOn() {
        CliDiagnostic diagnostic = new CliDiagnostic("", "", "", Diagnostic.Code.VALIDATION_ERROR,
                "unterminated record", "well-formed TSON", "a base-syntax error", Optional.of("2:1:7"),
                Optional.empty());

        assertEquals("[VALIDATION_ERROR] (2:1:7): unterminated record",
                OutputFormat.TEXT.render(new ValidationReport(false, List.of(diagnostic))));
    }

    @Test
    void jsonRendersAWellShapedObject() {
        String rendered = OutputFormat.JSON.render(ValidationReport.failed(Diagnostic.Code.VALIDATION_ERROR, "bad \"quote\""));
        assertEquals("{\"valid\":false,\"errors\":[{\"path\":\"\",\"schemaPointer\":\"\",\"schemaId\":\"\","
                + "\"code\":\"VALIDATION_ERROR\","
                + "\"message\":\"bad \\\"quote\\\"\",\"expected\":\"\",\"actual\":\"\","
                + "\"dataPosition\":null,\"schemaPosition\":null}]}", rendered);
    }

    @Test
    void jsonRendersAnEmptyErrorsArrayForAValidReport() {
        assertEquals("{\"valid\":true,\"errors\":[]}", OutputFormat.JSON.render(ValidationReport.ok()));
    }

    @Test
    void jsonRendersPositionsWhenPresent() {
        CliDiagnostic diagnostic = new CliDiagnostic("/value", "", "", Diagnostic.Code.FIELD_REQUIRED, "missing", "a value",
                "(absent)", Optional.of("1:1:0"), Optional.of("6:3:42"));
        String rendered = OutputFormat.JSON.render(new ValidationReport(false, List.of(diagnostic)));
        assertTrue(rendered.contains("\"dataPosition\":\"1:1:0\""), rendered);
        assertTrue(rendered.contains("\"schemaPosition\":\"6:3:42\""), rendered);
    }

    /**
     * The actual point of {@code --output tson}: the emitted text isn't just TSON-shaped, it's
     * genuinely readable back through {@code diagnostics.tn}'s own compiled {@code
     * validation_report} reader -- the dogfooding claim, proven, not just asserted in a comment.
     */
    @Test
    void tsonOutputGenuinelyRoundTripsThroughTheDiagnosticsSchema() {
        ValidationReport original = ValidationReport.failed(Diagnostic.Code.VALIDATION_ERROR, "value out of range");

        String rendered = OutputFormat.TSON.render(original);

        Object reread = DiagnosticsSchema.compiled().get("validation_report")
                .read(TestDocuments.document(rendered));

        assertEquals(original, reread);
    }

    @Test
    void tsonOutputRoundTripsAValidReportToo() {
        ValidationReport original = ValidationReport.ok();

        String rendered = OutputFormat.TSON.render(original);

        Object reread = DiagnosticsSchema.compiled().get("validation_report")
                .read(TestDocuments.document(rendered));

        assertEquals(original, reread);
    }

    @Test
    void tsonOutputRoundTripsMultipleErrors() {
        ValidationReport original = new ValidationReport(false, List.of(
                new CliDiagnostic("/a", "", "", Diagnostic.Code.VALIDATION_ERROR, "first problem", "", "",
                        Optional.empty(), Optional.empty()),
                new CliDiagnostic("/b", "", "", Diagnostic.Code.VALIDATION_ERROR, "second problem", "", "",
                        Optional.empty(), Optional.empty())));

        String rendered = OutputFormat.TSON.render(original);

        Object reread = DiagnosticsSchema.compiled().get("validation_report")
                .read(TestDocuments.document(rendered));

        assertEquals(original, reread);
        assertTrue(rendered.contains("first problem"));
        assertTrue(rendered.contains("second problem"));
    }

    @Test
    void tsonOutputRoundTripsRealPositions() {
        ValidationReport original = new ValidationReport(false, List.of(
                new CliDiagnostic("/value", "", "", Diagnostic.Code.FIELD_REQUIRED, "missing required field 'value'",
                        "a value", "(absent)", Optional.of("1:1:0"), Optional.of("6:3:42"))));

        String rendered = OutputFormat.TSON.render(original);

        Object reread = DiagnosticsSchema.compiled().get("validation_report")
                .read(TestDocuments.document(rendered));

        assertEquals(original, reread);
    }
}
