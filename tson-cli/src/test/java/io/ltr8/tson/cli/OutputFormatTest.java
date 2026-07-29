package io.ltr8.tson.cli;

import io.ltr8.tson.parser.TsonDataParser;
import org.junit.jupiter.api.Test;

import java.util.List;

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
        String rendered = OutputFormat.TEXT.render(ValidationReport.failed("VALIDATION_ERROR", "boom"));
        assertEquals("[VALIDATION_ERROR] boom", rendered);
    }

    @Test
    void jsonRendersAWellShapedObject() {
        String rendered = OutputFormat.JSON.render(ValidationReport.failed("VALIDATION_ERROR", "bad \"quote\""));
        assertEquals("{\"valid\":false,\"errors\":[{\"code\":\"VALIDATION_ERROR\",\"message\":\"bad \\\"quote\\\"\"}]}",
                rendered);
    }

    @Test
    void jsonRendersAnEmptyErrorsArrayForAValidReport() {
        assertEquals("{\"valid\":true,\"errors\":[]}", OutputFormat.JSON.render(ValidationReport.ok()));
    }

    /**
     * The actual point of {@code --output tson}: the emitted text isn't just TSON-shaped, it's
     * genuinely readable back through {@code diagnostics.tn}'s own compiled {@code
     * validation_report} reader -- the dogfooding claim, proven, not just asserted in a comment.
     */
    @Test
    void tsonOutputGenuinelyRoundTripsThroughTheDiagnosticsSchema() {
        ValidationReport original = ValidationReport.failed("VALIDATION_ERROR", "value out of range");

        String rendered = OutputFormat.TSON.render(original);

        Object reread = DiagnosticsSchema.compiled().compiledSchema().get("validation_report")
                .read(new TsonDataParser(rendered).parseDocument().root());

        assertEquals(original, reread);
    }

    @Test
    void tsonOutputRoundTripsAValidReportToo() {
        ValidationReport original = ValidationReport.ok();

        String rendered = OutputFormat.TSON.render(original);

        Object reread = DiagnosticsSchema.compiled().compiledSchema().get("validation_report")
                .read(new TsonDataParser(rendered).parseDocument().root());

        assertEquals(original, reread);
    }

    @Test
    void tsonOutputRoundTripsMultipleErrors() {
        ValidationReport original = new ValidationReport(false, List.of(
                new CliDiagnostic("VALIDATION_ERROR", "first problem"),
                new CliDiagnostic("VALIDATION_ERROR", "second problem")));

        String rendered = OutputFormat.TSON.render(original);

        Object reread = DiagnosticsSchema.compiled().compiledSchema().get("validation_report")
                .read(new TsonDataParser(rendered).parseDocument().root());

        assertEquals(original, reread);
        assertTrue(rendered.contains("first problem"));
        assertTrue(rendered.contains("second problem"));
    }
}
