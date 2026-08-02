package io.ltr8.tson.cli;

import io.ltr8.tson.compiler.Diagnostic;
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
        assertThrows(IllegalArgumentException.class, () -> OutputFormat.parse("yaml"));
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
        CliDiagnostic diagnostic = new CliDiagnostic("/value", Diagnostic.Code.FIELD_REQUIRED, "missing", "a value",
                "(absent)", Optional.empty(), Optional.empty());
        String rendered = OutputFormat.TEXT.render(new ValidationReport(false, List.of(diagnostic)));
        assertEquals("[FIELD_REQUIRED] /value: missing", rendered);
    }

    @Test
    void jsonRendersAWellShapedObject() {
        String rendered = OutputFormat.JSON.render(ValidationReport.failed(Diagnostic.Code.VALIDATION_ERROR, "bad \"quote\""));
        assertEquals("{\"valid\":false,\"errors\":[{\"path\":\"\",\"code\":\"VALIDATION_ERROR\","
                + "\"message\":\"bad \\\"quote\\\"\",\"expected\":\"\",\"actual\":\"\","
                + "\"dataPosition\":null,\"schemaPosition\":null}]}", rendered);
    }

    @Test
    void jsonRendersAnEmptyErrorsArrayForAValidReport() {
        assertEquals("{\"valid\":true,\"errors\":[]}", OutputFormat.JSON.render(ValidationReport.ok()));
    }

    @Test
    void jsonRendersPositionsWhenPresent() {
        CliDiagnostic diagnostic = new CliDiagnostic("/value", Diagnostic.Code.FIELD_REQUIRED, "missing", "a value",
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
                .read(rendered);

        assertEquals(original, reread);
    }

    @Test
    void tsonOutputRoundTripsAValidReportToo() {
        ValidationReport original = ValidationReport.ok();

        String rendered = OutputFormat.TSON.render(original);

        Object reread = DiagnosticsSchema.compiled().get("validation_report")
                .read(rendered);

        assertEquals(original, reread);
    }

    @Test
    void tsonOutputRoundTripsMultipleErrors() {
        ValidationReport original = new ValidationReport(false, List.of(
                new CliDiagnostic("/a", Diagnostic.Code.VALIDATION_ERROR, "first problem", "", "",
                        Optional.empty(), Optional.empty()),
                new CliDiagnostic("/b", Diagnostic.Code.VALIDATION_ERROR, "second problem", "", "",
                        Optional.empty(), Optional.empty())));

        String rendered = OutputFormat.TSON.render(original);

        Object reread = DiagnosticsSchema.compiled().get("validation_report")
                .read(rendered);

        assertEquals(original, reread);
        assertTrue(rendered.contains("first problem"));
        assertTrue(rendered.contains("second problem"));
    }

    @Test
    void tsonOutputRoundTripsRealPositions() {
        ValidationReport original = new ValidationReport(false, List.of(
                new CliDiagnostic("/value", Diagnostic.Code.FIELD_REQUIRED, "missing required field 'value'",
                        "a value", "(absent)", Optional.of("1:1:0"), Optional.of("6:3:42"))));

        String rendered = OutputFormat.TSON.render(original);

        Object reread = DiagnosticsSchema.compiled().get("validation_report")
                .read(rendered);

        assertEquals(original, reread);
    }
}
