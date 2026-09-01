package io.ltr8.tson.cli;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonSchemaFetchException;
import io.ltr8.tson.compiler.TsonUnicodeProcessorPolicy;
import io.ltr8.tson.compiler.TsonUnicodePolicy;
import io.ltr8.tson.compiler.TsonReadContext;
import org.junit.jupiter.api.Test;

import java.lang.Character.UnicodeScript;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutputFormatTest {

    /**
     * The default policy pair -- Highly Restrictive over whole names, Unrestricted over tokens, which is
     * what {@code TsonConfig}'s own {@code identifierPolicy}/{@code tokenPolicy} give a run that has
     * configured nothing. Every envelope in this file carries
     * one, because every envelope the CLI emits does: [TSON-DATA] §8.2's rules are the deployment's own
     * configuration, so a report that did not state them could not be interpreted anywhere but here.
     */
    private static final CliPolicy POLICY = CliPolicy.from(TsonUnicodeProcessorPolicy.of(
            TsonUnicodePolicy.highlyRestrictive(), TsonUnicodePolicy.unrestricted()));

    /** {@link #POLICY} as {@code --output json} writes it -- built from the accessor, not pinned to a version. */
    private static final String POLICY_JSON =
            "{\"identifierPolicy\":{\"level\":\"HIGHLY_RESTRICTIVE\",\"perSegment\":false,"
                    + "\"permitting\":[]},\"tokenPolicy\":{\"level\":\"UNRESTRICTED\","
                    + "\"perSegment\":false,\"permitting\":[]},"
                    + "\"unicodeDataVersion\":\"" + TsonUnicodePolicy.dataVersion() + "\"}";

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
        assertEquals("OK", OutputFormat.TEXT.render(ValidationReport.ok(POLICY)));
    }

    @Test
    void textRendersTheCodeAndMessageForAFailedReport() {
        String rendered = OutputFormat.TEXT.render(
                ValidationReport.failed(POLICY, Diagnostic.Code.VALIDATION_ERROR, "boom"));
        assertEquals("[VALIDATION_ERROR] boom", rendered);
    }

    @Test
    void textIncludesThePathWhenPresent() {
        CliDiagnostic diagnostic = new CliDiagnostic(Optional.of("/value"), Optional.empty(), Optional.empty(),
                Diagnostic.Code.FIELD_REQUIRED, "missing",
                Optional.of("a value"), Optional.of("(absent)"), Optional.empty(), Optional.empty(),
                Optional.empty());
        String rendered = OutputFormat.TEXT.render(new ValidationReport(false, POLICY, List.of(diagnostic)));
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
                Optional.empty());

        assertEquals("[TYPE_MISMATCH] /address/city (3:12:47): expected text",
                OutputFormat.TEXT.render(new ValidationReport(false, POLICY, List.of(diagnostic))));
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
                Optional.of("2:1:7"), Optional.empty(), Optional.empty());

        assertEquals("[VALIDATION_ERROR] (2:1:7): unterminated record",
                OutputFormat.TEXT.render(new ValidationReport(false, POLICY, List.of(diagnostic))));
    }

    /**
     * Everything with nothing to say renders {@code null}, not {@code ""} -- {@link CliDiagnostic#minimal}
     * has no location at either end, so both RFC 6901 pointers are absences here rather than roots.
     */
    @Test
    void jsonRendersAWellShapedObject() {
        String rendered = OutputFormat.JSON.render(
                ValidationReport.failed(POLICY, Diagnostic.Code.VALIDATION_ERROR, "bad \"quote\""));
        assertEquals("{\"valid\":false,\"policy\":" + POLICY_JSON
                + ",\"errors\":[{\"path\":null,\"schemaPointer\":null,\"schemaId\":null,"
                + "\"code\":\"VALIDATION_ERROR\","
                + "\"message\":\"bad \\\"quote\\\"\",\"expected\":null,\"actual\":null,"
                + "\"dataPosition\":null,\"schemaPosition\":null,\"fetchReason\":null}]}", rendered);
    }

    /** An empty string from {@link Diagnostic} is an absence, and crosses over as one. */
    @Test
    void anEmptyDiagnosticFieldBecomesAnAbsentOne() {
        CliDiagnostic converted = CliDiagnostic.from(new Diagnostic(Optional.of(""), Optional.empty(), "",
                Diagnostic.Code.TYPE_MISMATCH, "nope", "", "", Optional.empty(), Optional.empty(),
                Optional.empty()));

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
                Optional.empty()));
        CliDiagnostic schema = CliDiagnostic.from(
                Diagnostic.ofSchemaError("example.test/s.tn", "", "cannot load !!import", Optional.empty()));

        assertEquals(Optional.of(""), read.path(), "the data root, not an absence");
        assertEquals(Optional.empty(), read.schemaPointer(), "a read diagnostic has no schema end");
        assertEquals(Optional.empty(), schema.path(), "a schema diagnostic has no data end");
        assertEquals(Optional.of(""), schema.schemaPointer(), "the schema root, not an absence");
    }

    @Test
    void jsonRendersAnEmptyErrorsArrayForAValidReport() {
        assertEquals("{\"valid\":true,\"policy\":" + POLICY_JSON + ",\"errors\":[]}",
                OutputFormat.JSON.render(ValidationReport.ok(POLICY)));
    }

    @Test
    void jsonRendersPositionsWhenPresent() {
        CliDiagnostic diagnostic = new CliDiagnostic(Optional.of("/value"), Optional.empty(), Optional.empty(),
                Diagnostic.Code.FIELD_REQUIRED, "missing",
                Optional.of("a value"), Optional.of("(absent)"), Optional.of("1:1:0"), Optional.of("6:3:42"),
                Optional.empty());
        String rendered = OutputFormat.JSON.render(new ValidationReport(false, POLICY, List.of(diagnostic)));
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
        ValidationReport original = ValidationReport.failed(POLICY, Diagnostic.Code.VALIDATION_ERROR, "value out of range");

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
        ValidationReport original = new ValidationReport(false, POLICY, List.of(
                new CliDiagnostic(Optional.empty(), Optional.of(""), Optional.empty(),
                        Diagnostic.Code.SCHEMA_UNAVAILABLE, "cannot fetch schema 'https://nope.test/s.tn'",
                        Optional.of("a schema that can be obtained"), Optional.of("https://nope.test/s.tn"),
                        Optional.empty(), Optional.empty(),
                        Optional.of(TsonSchemaFetchException.Reason.NOT_PERMITTED))));

        String rendered = OutputFormat.TSON.render(original);
        Object reread = DiagnosticsSchema.compiled().get("validation_report")
                .read(TestDocuments.document(rendered));

        assertEquals(original, reread);
        assertTrue(rendered.contains("NOT_PERMITTED"), rendered);
    }

    /**
     * <b>A [TSON-DATA] §8.2 refusal round-trips as an ordinary diagnostic</b> -- which rule refused is its
     * {@code code} and nothing else rides on it -- <b>while the version §8.2 requires a refusal to name
     * round-trips once, on the envelope.</b> That is what keeps a stored or forwarded report interpretable:
     * §8.3 marks all three rules unstable across Unicode releases, so a consumer reading this back later
     * still learns which tables and which level produced the verdict, without every refusal restating one
     * constant.
     */
    @Test
    void tsonOutputRoundTripsARefusalAndTheRunsPolicy() {
        ValidationReport original = new ValidationReport(false, POLICY, List.of(
                new CliDiagnostic(Optional.empty(), Optional.of("/admin"), Optional.empty(),
                        Diagnostic.Code.RESTRICTED_SCRIPT, "declared name mixes scripts",
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty())));

        String rendered = OutputFormat.TSON.render(original);
        Object reread = DiagnosticsSchema.compiled().get("validation_report")
                .read(TestDocuments.document(rendered));

        assertEquals(original, reread);
        assertTrue(rendered.contains("RESTRICTED_SCRIPT"), rendered);
        assertTrue(rendered.contains(TsonUnicodePolicy.dataVersion()), rendered);
    }

    /**
     * <b>A relaxation round-trips too</b>, and it is the half most likely to explain a disagreement: a level
     * is usually the default, where {@code permitting(LATIN, CYRILLIC)} is by definition a local decision.
     * The nested list is the shape {@code diagnostics.tn} declares as {@code [[text]]}, so this is also what
     * proves that declaration binds.
     */
    @Test
    void tsonOutputRoundTripsARelaxedPolicy() {
        CliPolicy relaxed = CliPolicy.from(TsonUnicodeProcessorPolicy.of(
                TsonUnicodePolicy.moderatelyRestrictive().perSegment()
                        .permitting(UnicodeScript.LATIN, UnicodeScript.CYRILLIC),
                TsonUnicodePolicy.unrestricted()));
        ValidationReport original = ValidationReport.ok(relaxed);

        String rendered = OutputFormat.TSON.render(original);
        Object reread = DiagnosticsSchema.compiled().get("validation_report")
                .read(TestDocuments.document(rendered));

        assertEquals(original, reread);
        assertEquals(List.of(List.of("CYRILLIC", "LATIN")), relaxed.identifierPolicy().permitting());
        assertTrue(relaxed.identifierPolicy().perSegment());
    }

    /**
     * <b>The policy is stated once per envelope, and no diagnostic carries a copy.</b> The fact is constant
     * for the run -- twenty refusals cannot disagree about which tables refused them -- so a per-diagnostic
     * copy would be N copies of one string, free to drift and impossible to reconcile if it did.
     */
    @Test
    void jsonStatesThePolicyOnceAndNotOnEachDiagnostic() {
        CliDiagnostic refused = new CliDiagnostic(Optional.empty(), Optional.empty(), Optional.empty(),
                Diagnostic.Code.CONFUSABLE_NAMES, "reads alike", Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty());

        String rendered = OutputFormat.JSON.render(new ValidationReport(false, POLICY, List.of(refused)));

        assertEquals(1, count(rendered, TsonUnicodePolicy.dataVersion()), rendered);
        assertTrue(rendered.contains("\"policy\":" + POLICY_JSON), rendered);
        assertTrue(rendered.contains("\"code\":\"CONFUSABLE_NAMES\""), rendered);
    }

    private static int count(String haystack, String needle) {
        int found = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            found++;
        }
        return found;
    }

    /**
     * <b>{@link OutputFormat#TEXT} prints the policy only when something was actually refused</b>, where the
     * machine formats always carry it. A person reading a terminal does not want a configuration dump on
     * every clean run; what they do want, at the moment a name is refused, is to be told the verdict came
     * from this deployment's settings rather than from the file they are looking at -- which is the one
     * question whose answer is not in that file.
     */
    @Test
    void textPrintsThePolicyOnlyWhenSomethingWasRefused() {
        String refused = OutputFormat.TEXT.render(new ValidationReport(false, POLICY, List.of(
                CliDiagnostic.minimal(Diagnostic.Code.RESTRICTED_SCRIPT, "mixes scripts"))));
        assertTrue(refused.contains("[RESTRICTED_SCRIPT] mixes scripts"), refused);
        assertTrue(refused.contains("note: refused under identifier policy HIGHLY_RESTRICTIVE,"
                + " token policy UNRESTRICTED, Unicode " + TsonUnicodePolicy.dataVersion()), refused);

        String ordinary = OutputFormat.TEXT.render(
                ValidationReport.failed(POLICY, Diagnostic.Code.TYPE_MISMATCH, "nope"));
        assertEquals("[TYPE_MISMATCH] nope", ordinary);
        assertEquals("OK", OutputFormat.TEXT.render(ValidationReport.ok(POLICY)));
    }

    /**
     * <b>And on a clean run whose policy was configured</b>, which is the case [TSON-DATA] §8.2's
     * non-silence rule is actually about: a run given {@code --identifier-policy unrestricted} that printed
     * {@code OK} and nothing else would have relaxed a security rule invisibly. The default stays quiet --
     * a person does not want a configuration dump on every green run, and nothing was relaxed.
     */
    @Test
    void textPrintsANonDefaultPolicyEvenWhenNothingWasRefused() {
        CliPolicy relaxed = CliPolicy.from(TsonUnicodeProcessorPolicy.of(
                TsonUnicodePolicy.scriptsUnchecked(), TsonUnicodePolicy.unrestricted()));

        String rendered = OutputFormat.TEXT.render(ValidationReport.ok(relaxed));

        assertTrue(rendered.startsWith("OK"), rendered);
        assertTrue(rendered.contains("note: judged under identifier policy MINIMALLY_RESTRICTIVE"), rendered);
        assertFalse(rendered.contains("refused"), rendered);
    }

    /**
     * <b>{@code tson policy} prints the same record the envelopes carry</b>, with no document in hand -- the
     * surface a generator reads before it writes, which is where a one-shot repair actually comes from. The
     * TSON form reads back through {@code diagnostics.tn}'s own {@code policy} reader, so what it prints is
     * genuinely the wire shape rather than something merely similar to it.
     */
    @Test
    void policyRendersOnItsOwnInEveryFormat() {
        assertEquals(POLICY_JSON, OutputFormat.JSON.render(POLICY));

        String text = OutputFormat.TEXT.render(POLICY);
        assertEquals("identifier policy: HIGHLY_RESTRICTIVE" + System.lineSeparator()
                + "token policy:      UNRESTRICTED" + System.lineSeparator()
                + "unicode data:      " + TsonUnicodePolicy.dataVersion(), text);

        Object reread = DiagnosticsSchema.compiled().get("policy")
                .read(TestDocuments.document(OutputFormat.TSON.render(POLICY)));
        assertEquals(POLICY, reread);
    }

    /** The same value in {@code --output json}, where a consumer reads a name rather than a bound enum. */
    @Test
    void jsonRendersTheFetchReasonAndNullWhereThereIsNone() {
        CliDiagnostic unavailable = new CliDiagnostic(Optional.empty(), Optional.of(""), Optional.empty(),
                Diagnostic.Code.SCHEMA_UNAVAILABLE, "cannot fetch", Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(TsonSchemaFetchException.Reason.TIMEOUT));

        assertTrue(OutputFormat.JSON.render(new ValidationReport(false, POLICY, List.of(unavailable)))
                .contains("\"fetchReason\":\"TIMEOUT\""));
        assertTrue(OutputFormat.JSON.render(ValidationReport.failed(POLICY, Diagnostic.Code.TYPE_MISMATCH, "nope"))
                .contains("\"fetchReason\":null"));
    }

    @Test
    void tsonOutputRoundTripsAValidReportToo() {
        ValidationReport original = ValidationReport.ok(POLICY);

        String rendered = OutputFormat.TSON.render(original);

        Object reread = DiagnosticsSchema.compiled().get("validation_report")
                .read(TestDocuments.document(rendered));

        assertEquals(original, reread);
    }

    @Test
    void tsonOutputRoundTripsMultipleErrors() {
        ValidationReport original = new ValidationReport(false, POLICY, List.of(
                new CliDiagnostic(Optional.of("/a"), Optional.empty(), Optional.empty(),
                        Diagnostic.Code.VALIDATION_ERROR, "first problem",
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty()),
                new CliDiagnostic(Optional.of("/b"), Optional.empty(), Optional.empty(),
                        Diagnostic.Code.VALIDATION_ERROR, "second problem",
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty())));

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
        ValidationRun run = ValidationRun.of(POLICY, List.of(FileReport.of("a.tn", List.of())));

        assertEquals("{\"valid\":true,\"policy\":" + POLICY_JSON
                        + ",\"files\":[{\"file\":\"a.tn\",\"valid\":true,\"errors\":[]}],\"errors\":[]}",
                OutputFormat.JSON.render(run));
    }

    @Test
    void jsonNamesEachFileAndCarriesItsOwnVerdict() {
        ValidationRun run = ValidationRun.of(POLICY, List.of(
                FileReport.of("good.tn", List.of()),
                FileReport.of("bad.tn", List.of(CliDiagnostic.minimal(Diagnostic.Code.TYPE_MISMATCH, "nope")))));

        String rendered = OutputFormat.JSON.render(run);

        assertEquals("{\"valid\":false,\"policy\":" + POLICY_JSON + ",\"files\":["
                + "{\"file\":\"good.tn\",\"valid\":true,\"errors\":[]},"
                + "{\"file\":\"bad.tn\",\"valid\":false,\"errors\":[{\"path\":null,\"schemaPointer\":null,"
                + "\"schemaId\":null,\"code\":\"TYPE_MISMATCH\",\"message\":\"nope\",\"expected\":null,"
                + "\"actual\":null,\"dataPosition\":null,\"schemaPosition\":null,\"fetchReason\":null}]}"
                + "],\"errors\":[]}", rendered);
    }

    /**
     * A run-level failure -- one that stopped the invocation before any document was read -- keeps the
     * same envelope, with no files in it. That is the shape a consumer meets on exit 2, and meeting a
     * second shape there is exactly what this envelope exists to prevent.
     */
    @Test
    void jsonRendersARunThatNeverReachedADocument() {
        ValidationRun run = ValidationRun.failed(POLICY, Diagnostic.Code.VALIDATION_ERROR, "no data files");

        String rendered = OutputFormat.JSON.render(run);

        assertTrue(rendered.startsWith("{\"valid\":false,\"policy\":" + POLICY_JSON
                + ",\"files\":[],\"errors\":[{"), rendered);
        assertTrue(rendered.contains("\"message\":\"no data files\""), rendered);
    }

    /** The {@code # <file>} label is text-only, and only when there is more than one file to tell apart. */
    @Test
    void textLabelsEachFileOnlyWhenThereIsMoreThanOne() {
        FileReport bad = FileReport.of("bad.tn", List.of(
                CliDiagnostic.minimal(Diagnostic.Code.TYPE_MISMATCH, "nope")));

        assertEquals("[TYPE_MISMATCH] nope", OutputFormat.TEXT.render(ValidationRun.of(POLICY, List.of(bad))));
        assertEquals("# good.tn" + System.lineSeparator() + "OK" + System.lineSeparator()
                        + "# bad.tn" + System.lineSeparator() + "[TYPE_MISMATCH] nope",
                OutputFormat.TEXT.render(ValidationRun.of(POLICY, List.of(FileReport.of("good.tn", List.of()), bad))));
    }

    @Test
    void tsonOutputRoundTripsARunThroughTheDiagnosticsSchema() {
        ValidationRun original = ValidationRun.of(POLICY, List.of(
                FileReport.of("good.tn", List.of()),
                FileReport.of("bad.tn", List.of(new CliDiagnostic(Optional.of("/a"), Optional.empty(), Optional.empty(),
                        Diagnostic.Code.FIELD_REQUIRED,
                        "missing required field 'a'", Optional.of("a value"), Optional.of("(absent)"),
                        Optional.of("1:1:0"), Optional.of("6:3:42"), Optional.empty())))));

        String rendered = OutputFormat.TSON.render(original);

        Object reread = DiagnosticsSchema.compiled().get("validation_run")
                .read(TestDocuments.document(rendered));

        assertEquals(original, reread);
    }

    @Test
    void tsonOutputRoundTripsARunThatNeverReachedADocument() {
        ValidationRun original = ValidationRun.failed(POLICY, Diagnostic.Code.VALIDATION_ERROR, "no data files");

        String rendered = OutputFormat.TSON.render(original);

        Object reread = DiagnosticsSchema.compiled().get("validation_run")
                .read(TestDocuments.document(rendered));

        assertEquals(original, reread);
    }

    @Test
    void tsonOutputRoundTripsRealPositions() {
        ValidationReport original = new ValidationReport(false, POLICY, List.of(
                new CliDiagnostic(Optional.of("/value"), Optional.empty(), Optional.empty(), Diagnostic.Code.FIELD_REQUIRED,
                        "missing required field 'value'", Optional.of("a value"), Optional.of("(absent)"),
                        Optional.of("1:1:0"), Optional.of("6:3:42"), Optional.empty())));

        String rendered = OutputFormat.TSON.render(original);

        Object reread = DiagnosticsSchema.compiled().get("validation_report")
                .read(TestDocuments.document(rendered));

        assertEquals(original, reread);
    }
}
