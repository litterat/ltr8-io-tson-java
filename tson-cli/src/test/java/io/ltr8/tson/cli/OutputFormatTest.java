package io.ltr8.tson.cli;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonSchemaFetchException;
import io.ltr8.tson.compiler.TsonUnicodePolicy;
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
        CliDiagnostic diagnostic = new CliDiagnostic(Optional.of("/value"), Optional.empty(), Optional.empty(),
                Diagnostic.Code.FIELD_REQUIRED, "missing",
                Optional.of("a value"), Optional.of("(absent)"), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
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
        CliDiagnostic diagnostic = new CliDiagnostic(Optional.of("/address/city"), Optional.empty(), Optional.empty(),
                Diagnostic.Code.TYPE_MISMATCH,
                "expected text", Optional.of("text"), Optional.of("42"), Optional.of("3:12:47"), Optional.empty(),
                Optional.empty(), Optional.empty());

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
        CliDiagnostic diagnostic = new CliDiagnostic(Optional.of(""), Optional.empty(), Optional.empty(),
                Diagnostic.Code.VALIDATION_ERROR,
                "unterminated record", Optional.of("well-formed TSON"), Optional.of("a base-syntax error"),
                Optional.of("2:1:7"), Optional.empty(), Optional.empty(), Optional.empty());

        assertEquals("[VALIDATION_ERROR] (2:1:7): unterminated record",
                OutputFormat.TEXT.render(new ValidationReport(false, List.of(diagnostic))));
    }

    /**
     * Everything with nothing to say renders {@code null}, not {@code ""} -- {@link CliDiagnostic#minimal}
     * has no location at either end, so both RFC 6901 pointers are absences here rather than roots.
     */
    @Test
    void jsonRendersAWellShapedObject() {
        String rendered = OutputFormat.JSON.render(ValidationReport.failed(Diagnostic.Code.VALIDATION_ERROR, "bad \"quote\""));
        assertEquals("{\"valid\":false,\"errors\":[{\"path\":null,\"schemaPointer\":null,\"schemaId\":null,"
                + "\"code\":\"VALIDATION_ERROR\","
                + "\"message\":\"bad \\\"quote\\\"\",\"expected\":null,\"actual\":null,"
                + "\"dataPosition\":null,\"schemaPosition\":null,\"fetchReason\":null,"
                + "\"unicodeDataVersion\":null}]}", rendered);
    }

    /** An empty string from {@link Diagnostic} is an absence, and crosses over as one. */
    @Test
    void anEmptyDiagnosticFieldBecomesAnAbsentOne() {
        CliDiagnostic converted = CliDiagnostic.from(new Diagnostic(Optional.of(""), Optional.empty(), "",
                Diagnostic.Code.TYPE_MISMATCH, "nope", "", "", Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty()));

        assertEquals(Optional.empty(), converted.schemaId());
        assertEquals(Optional.empty(), converted.expected());
        assertEquals(Optional.empty(), converted.actual());
        assertEquals(Optional.empty(), converted.schemaPointer());
    }

    /**
     * The two pointers cross over untouched, and {@code ""} stays a value. A read diagnostic locates itself
     * at the data root and has no schema end at all; the renderers have to be able to tell those apart, and
     * folding both into {@code null} is what made them indistinguishable before.
     */
    @Test
    void aRootPointerIsAValueAndAnAbsentOneIsNot() {
        CliDiagnostic read = CliDiagnostic.from(new Diagnostic(Optional.of(""), Optional.empty(), "",
                Diagnostic.Code.VALIDATION_ERROR, "nope", "", "", Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty()));
        CliDiagnostic schema = CliDiagnostic.from(
                Diagnostic.ofSchemaError("example.test/s.tn", "", "cannot load !!import", Optional.empty()));

        assertEquals(Optional.of(""), read.path(), "the data root, not an absence");
        assertEquals(Optional.empty(), read.schemaPointer(), "a read diagnostic has no schema end");
        assertEquals(Optional.empty(), schema.path(), "a schema diagnostic has no data end");
        assertEquals(Optional.of(""), schema.schemaPointer(), "the schema root, not an absence");
    }

    @Test
    void jsonRendersAnEmptyErrorsArrayForAValidReport() {
        assertEquals("{\"valid\":true,\"errors\":[]}", OutputFormat.JSON.render(ValidationReport.ok()));
    }

    @Test
    void jsonRendersPositionsWhenPresent() {
        CliDiagnostic diagnostic = new CliDiagnostic(Optional.of("/value"), Optional.empty(), Optional.empty(),
                Diagnostic.Code.FIELD_REQUIRED, "missing",
                Optional.of("a value"), Optional.of("(absent)"), Optional.of("1:1:0"), Optional.of("6:3:42"),
                Optional.empty(), Optional.empty());
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

    /**
     * <b>A fetch reason survives the round trip as the enum it is</b>, which is what makes carrying it
     * structurally worth anything: a consumer of {@code --output tson} reads back {@code NOT_PERMITTED}
     * and routes on it, where a rendered string would leave it matching on text. This is also the check
     * that {@code fetch_reason} is really declared and really bound -- an unbound enum name would read
     * back as something else or not at all.
     */
    @Test
    void tsonOutputRoundTripsAFetchReason() {
        ValidationReport original = new ValidationReport(false, List.of(
                new CliDiagnostic(Optional.empty(), Optional.of(""), Optional.empty(),
                        Diagnostic.Code.SCHEMA_UNAVAILABLE, "cannot fetch schema 'https://nope.test/s.tn'",
                        Optional.of("a schema that can be obtained"), Optional.of("https://nope.test/s.tn"),
                        Optional.empty(), Optional.empty(),
                        Optional.of(TsonSchemaFetchException.Reason.NOT_PERMITTED), Optional.empty())));

        String rendered = OutputFormat.TSON.render(original);
        Object reread = DiagnosticsSchema.compiled().get("validation_report")
                .read(TestDocuments.document(rendered));

        assertEquals(original, reread);
        assertTrue(rendered.contains("NOT_PERMITTED"), rendered);
    }

    /**
     * <b>A [TSON-DATA] §8.2 refusal survives the round trip carrying its data version.</b> §8.3 marks all
     * three rules unstable across Unicode releases, so a consumer of {@code --output tson} that could
     * not read back which tables refused a name would hold a report it cannot interpret later. Which
     * rule refused is the {@code code}, which round-trips as any other.
     */
    @Test
    void tsonOutputRoundTripsARefusalsDataVersion() {
        ValidationReport original = new ValidationReport(false, List.of(
                new CliDiagnostic(Optional.empty(), Optional.of("/admin"), Optional.empty(),
                        Diagnostic.Code.RESTRICTED_SCRIPT, "declared name mixes scripts",
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.of(TsonUnicodePolicy.dataVersion()))));

        String rendered = OutputFormat.TSON.render(original);
        Object reread = DiagnosticsSchema.compiled().get("validation_report")
                .read(TestDocuments.document(rendered));

        assertEquals(original, reread);
        assertTrue(rendered.contains("RESTRICTED_SCRIPT"), rendered);
    }

    /**
     * The same in {@code --output json}: a flat string, and {@code null} where a diagnostic is an ordinary
     * verdict rather than a refusal.
     */
    @Test
    void jsonRendersTheDataVersionAndNullWhereThereIsNone() {
        CliDiagnostic refused = new CliDiagnostic(Optional.empty(), Optional.empty(), Optional.empty(),
                Diagnostic.Code.CONFUSABLE_NAMES, "reads alike", Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("16.0"));

        assertTrue(OutputFormat.JSON.render(new ValidationReport(false, List.of(refused)))
                .contains("\"unicodeDataVersion\":\"16.0\""));
        assertTrue(OutputFormat.JSON.render(ValidationReport.failed(Diagnostic.Code.TYPE_MISMATCH, "nope"))
                .contains("\"unicodeDataVersion\":null"));
    }

    /** The same value in {@code --output json}, where a consumer reads a name rather than a bound enum. */
    @Test
    void jsonRendersTheFetchReasonAndNullWhereThereIsNone() {
        CliDiagnostic unavailable = new CliDiagnostic(Optional.empty(), Optional.of(""), Optional.empty(),
                Diagnostic.Code.SCHEMA_UNAVAILABLE, "cannot fetch", Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(TsonSchemaFetchException.Reason.TIMEOUT), Optional.empty());

        assertTrue(OutputFormat.JSON.render(new ValidationReport(false, List.of(unavailable)))
                .contains("\"fetchReason\":\"TIMEOUT\""));
        assertTrue(OutputFormat.JSON.render(ValidationReport.failed(Diagnostic.Code.TYPE_MISMATCH, "nope"))
                .contains("\"fetchReason\":null"));
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
                new CliDiagnostic(Optional.of("/a"), Optional.empty(), Optional.empty(),
                        Diagnostic.Code.VALIDATION_ERROR, "first problem",
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty()),
                new CliDiagnostic(Optional.of("/b"), Optional.empty(), Optional.empty(),
                        Diagnostic.Code.VALIDATION_ERROR, "second problem",
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty())));

        String rendered = OutputFormat.TSON.render(original);

        Object reread = DiagnosticsSchema.compiled().get("validation_report")
                .read(TestDocuments.document(rendered));

        assertEquals(original, reread);
        assertTrue(rendered.contains("first problem"));
        assertTrue(rendered.contains("second problem"));
    }

    // --- the run envelope (what `validate` emits, whatever the file count) ---

    @Test
    void jsonRendersTheEnvelopeForASingleFileToo() {
        ValidationRun run = ValidationRun.of(List.of(FileReport.of("a.tn", List.of())));

        assertEquals("{\"valid\":true,\"files\":[{\"file\":\"a.tn\",\"valid\":true,\"errors\":[]}],\"errors\":[]}",
                OutputFormat.JSON.render(run));
    }

    @Test
    void jsonNamesEachFileAndCarriesItsOwnVerdict() {
        ValidationRun run = ValidationRun.of(List.of(
                FileReport.of("good.tn", List.of()),
                FileReport.of("bad.tn", List.of(CliDiagnostic.minimal(Diagnostic.Code.TYPE_MISMATCH, "nope")))));

        String rendered = OutputFormat.JSON.render(run);

        assertEquals("{\"valid\":false,\"files\":["
                + "{\"file\":\"good.tn\",\"valid\":true,\"errors\":[]},"
                + "{\"file\":\"bad.tn\",\"valid\":false,\"errors\":[{\"path\":null,\"schemaPointer\":null,"
                + "\"schemaId\":null,\"code\":\"TYPE_MISMATCH\",\"message\":\"nope\",\"expected\":null,"
                + "\"actual\":null,\"dataPosition\":null,\"schemaPosition\":null,\"fetchReason\":null,"
                + "\"unicodeDataVersion\":null}]}"
                + "],\"errors\":[]}", rendered);
    }

    /**
     * A run-level failure -- one that stopped the invocation before any document was read -- keeps the
     * same envelope, with no files in it. That is the shape a consumer meets on exit 2, and meeting a
     * second shape there is exactly what this envelope exists to prevent.
     */
    @Test
    void jsonRendersARunThatNeverReachedADocument() {
        ValidationRun run = ValidationRun.failed(Diagnostic.Code.VALIDATION_ERROR, "no data files");

        String rendered = OutputFormat.JSON.render(run);

        assertTrue(rendered.startsWith("{\"valid\":false,\"files\":[],\"errors\":[{"), rendered);
        assertTrue(rendered.contains("\"message\":\"no data files\""), rendered);
    }

    /** The {@code # <file>} label is text-only, and only when there is more than one file to tell apart. */
    @Test
    void textLabelsEachFileOnlyWhenThereIsMoreThanOne() {
        FileReport bad = FileReport.of("bad.tn", List.of(
                CliDiagnostic.minimal(Diagnostic.Code.TYPE_MISMATCH, "nope")));

        assertEquals("[TYPE_MISMATCH] nope", OutputFormat.TEXT.render(ValidationRun.of(List.of(bad))));
        assertEquals("# good.tn" + System.lineSeparator() + "OK" + System.lineSeparator()
                        + "# bad.tn" + System.lineSeparator() + "[TYPE_MISMATCH] nope",
                OutputFormat.TEXT.render(ValidationRun.of(List.of(FileReport.of("good.tn", List.of()), bad))));
    }

    @Test
    void tsonOutputRoundTripsARunThroughTheDiagnosticsSchema() {
        ValidationRun original = ValidationRun.of(List.of(
                FileReport.of("good.tn", List.of()),
                FileReport.of("bad.tn", List.of(new CliDiagnostic(Optional.of("/a"), Optional.empty(), Optional.empty(),
                        Diagnostic.Code.FIELD_REQUIRED,
                        "missing required field 'a'", Optional.of("a value"), Optional.of("(absent)"),
                        Optional.of("1:1:0"), Optional.of("6:3:42"), Optional.empty(), Optional.empty())))));

        String rendered = OutputFormat.TSON.render(original);

        Object reread = DiagnosticsSchema.compiled().get("validation_run")
                .read(TestDocuments.document(rendered));

        assertEquals(original, reread);
    }

    @Test
    void tsonOutputRoundTripsARunThatNeverReachedADocument() {
        ValidationRun original = ValidationRun.failed(Diagnostic.Code.VALIDATION_ERROR, "no data files");

        String rendered = OutputFormat.TSON.render(original);

        Object reread = DiagnosticsSchema.compiled().get("validation_run")
                .read(TestDocuments.document(rendered));

        assertEquals(original, reread);
    }

    @Test
    void tsonOutputRoundTripsRealPositions() {
        ValidationReport original = new ValidationReport(false, List.of(
                new CliDiagnostic(Optional.of("/value"), Optional.empty(), Optional.empty(), Diagnostic.Code.FIELD_REQUIRED,
                        "missing required field 'value'", Optional.of("a value"), Optional.of("(absent)"),
                        Optional.of("1:1:0"), Optional.of("6:3:42"), Optional.empty(), Optional.empty())));

        String rendered = OutputFormat.TSON.render(original);

        Object reread = DiagnosticsSchema.compiled().get("validation_report")
                .read(TestDocuments.document(rendered));

        assertEquals(original, reread);
    }
}
