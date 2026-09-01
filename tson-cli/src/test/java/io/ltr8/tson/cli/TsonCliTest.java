package io.ltr8.tson.cli;

import io.ltr8.tson.compiler.Diagnostic;

import io.ltr8.tson.compiler.TsonUnicodePolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TsonCliTest {

    @Test
    void noArgumentsExitsTwoWithUsage() throws IOException {
        String err = captureStderr(() -> assertEquals(2, TsonCli.run(new String[0])));
        assertTrue(err.contains("usage:"), err);
    }

    @Test
    void anUnknownCommandExitsTwo() throws IOException {
        String err = captureStderr(() -> assertEquals(2, TsonCli.run(new String[] {"frobnicate"})));
        assertTrue(err.contains("unknown command"), err);
    }

    @Test
    void aBadFlagValueIsAUsageErrorWithUsagePrinted() throws IOException {
        String err = captureStderr(() ->
                assertEquals(2, TsonCli.run(new String[] {"validate", "--output", "yaml", "x.tn"})));
        assertTrue(err.contains("unknown --output format"), err);
        assertTrue(err.contains("usage:"), err);
    }

    /**
     * A fault gets its own exit code and its stack trace, rather than being folded into 1 ("your document is
     * invalid") or 2 ("your command line is wrong") -- the two things a caller could otherwise act on and
     * would be misled by. {@code Tson.validate} rethrows rather than reporting exactly so this can happen.
     */
    @Test
    void anInternalFaultExitsSeventyWithItsStackTrace() throws IOException {
        String err = captureStderr(() ->
                assertEquals(70, TsonCli.internalError(new IllegalStateException("a bug, not a bad document"))));

        assertTrue(err.contains("internal error"), err);
        assertTrue(err.contains("a bug, not a bad document"), err);
        assertTrue(err.contains("bug in tson, not a problem with your document"), err);
        assertTrue(err.contains("at io.ltr8.tson.cli"), err);          // a real stack trace, not a summary
        assertFalse(err.contains("usage:"), err);                      // never mistaken for a usage error
    }

    /**
     * A gap in this library shares the fault's exit code -- neither is a verdict on the document -- but not
     * its framing: the message is the whole report, since these messages routinely name the workaround and
     * the please-report-it banner plus a stack trace buried it.
     */
    @Test
    void anUnimplementedGapExitsSeventyWithItsMessageAndNoStackTrace() throws IOException {
        String err = captureStderr(() -> assertEquals(70, TsonCli.notImplemented(
                new UnsupportedOperationException("naming the inner form in its own declaration is the way"))));

        assertTrue(err.contains("not implemented yet"), err);
        assertTrue(err.contains("naming the inner form in its own declaration is the way"), err);
        assertFalse(err.contains("Please report it"), err);          // a known gap is not news
        assertFalse(err.contains("at io.ltr8.tson.cli"), err);       // no trace to bury the one line worth reading
        assertFalse(err.contains("usage:"), err);
    }

    /** A schema that loads clean and cannot be read against: {@code precision} is carried but not enforced. */
    private static final String GAP_SCHEMA = """
            !!id:"https://example.test/cli-gap.tn"
            !!meta:"https://tson.io/2026/34/m/meta.tn"
            !!import:"https://tson.io/2026/34/m/core.tn"
            {
              stamped => { at: unknown  n: int32 }
              plain   => { n: int32 }
            }
            """;

    /** Well formed, and valid as far as anything can tell -- the field it fills has no reader. */
    private static final String GAP_DOCUMENT = """
            !!schema:"https://example.test/cli-gap.tn"
            !stamped { at: 1  n: 1 }
            """;

    /**
     * <b>A mixed run takes the most permanent code.</b> 1 is a verdict on the document; 70 and 69 are the
     * absence of one, differing in who could not give it -- this library, or whoever was to serve the
     * schema. A gap outranks an unavailable schema because retrying fixes only the second, and a run that
     * holds both would reach the gap again on the retry. A run holding both a gap and an ordinary error is
     * therefore 70, not 1: something went unchecked, so "invalid" is not a verdict that run is entitled to
     * give, and the two codes are what let the CLI tell them apart in one pass.
     *
     * <p><b>Over the codes here, and end to end in {@link #aGapCostsItsOwnFieldAVerdictAndNoOthers}.</b> No
     * <em>schema</em> reaches a gap, so the mixed list this decides is reached end to end by a <em>read</em>
     * gap, arriving as a {@code NOT_IMPLEMENTED} diagnostic like any other. The unit form stays because it
     * states all three cases of the rule in one place, including the exit-1 case no gap fixture exercises.
     */
    @Test
    void aRunHoldingMoreThanOneKindOfProblemTakesTheMostPermanentCode() {
        assertEquals(70, TsonCli.exitCodeFor(List.of(
                Diagnostic.Code.NOT_IMPLEMENTED, Diagnostic.Code.SCHEMA_ERROR)));
        assertEquals(70, TsonCli.exitCodeFor(List.of(
                Diagnostic.Code.NOT_IMPLEMENTED, Diagnostic.Code.SCHEMA_UNAVAILABLE)));
        assertEquals(70, TsonCli.exitCodeFor(List.of(Diagnostic.Code.NOT_IMPLEMENTED)));
        assertEquals(69, TsonCli.exitCodeFor(List.of(
                Diagnostic.Code.SCHEMA_UNAVAILABLE, Diagnostic.Code.SCHEMA_ERROR)));
        assertEquals(69, TsonCli.exitCodeFor(List.of(Diagnostic.Code.SCHEMA_UNAVAILABLE)));
        assertEquals(1, TsonCli.exitCodeFor(List.of(Diagnostic.Code.SCHEMA_ERROR)));
    }

    /**
     * <b>A gap at read time is a gap, end to end.</b> The schema loads clean and the document is well
     * formed; what cannot be done is check one field against it, so the run is entitled to no verdict on
     * that field. It rides in the report as {@code NOT_IMPLEMENTED} -- with the data path and position of
     * the value that could not be read, like any other read diagnostic -- and {@link TsonCli#exitCodeFor}
     * lifts the run to 70 with the note on stderr, so the report on stdout stays exactly what {@code
     * --output json|tson} promises.
     *
     * <p>Never exit 1, which would tell a script the document was judged and rejected. Two constructors
     * reach this ({@code CLAUDE.md}); {@code unknown} is the cheapest to write.
     */
    @Test
    void aReadTimeGapIsAGapNotAVerdict(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "gap.tn", GAP_SCHEMA);
        Path data = writeFile(dir, "stamped.tn", GAP_DOCUMENT);

        String err = captureStderr(() -> {
            String out = captureStdout(() ->
                    assertEquals(70, TsonCli.run(new String[] {"validate", schema.toString(), data.toString()})));
            assertTrue(out.contains("NOT_IMPLEMENTED"), out);
            assertTrue(out.contains("has no compiled reader"), out);
            assertTrue(out.contains("/at"), () -> "located at the value it could not read: " + out);
        });
        assertTrue(err.contains("could not be checked"), err);
        assertFalse(err.contains("Please report it"), err);
    }

    /**
     * <b>A gap costs its own field a verdict and nothing else's.</b> This is the whole point of a gap
     * travelling as a code rather than as an exception, and it is checked in both directions at once: a run
     * holding one unreadable document and one plainly invalid document reports <em>both</em>, and a single
     * document holding a gap and an ordinary error reports both of those too.
     *
     * <p>Throwing instead lost the entire envelope -- stdout was empty and the invalid document was never
     * judged, in either order -- which is exactly the failure the schema pipeline gave up throwing gaps to
     * avoid: "one unimplemented construct, and a document with three ordinary mistakes reported none of
     * them". The read side was the last place that survived.
     */
    @Test
    void aGapCostsItsOwnFieldAVerdictAndNoOthers(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "gap.tn", GAP_SCHEMA);
        Path gap = writeFile(dir, "stamped.tn", GAP_DOCUMENT);
        Path invalid = writeFile(dir, "plain.tn", """
                !!schema:"https://example.test/cli-gap.tn"
                !plain { n: "nope" }
                """);
        Path both = writeFile(dir, "both.tn", """
                !!schema:"https://example.test/cli-gap.tn"
                !stamped { at: 1  n: "nope" }
                """);

        for (List<String> run : List.of(List.of(gap.toString(), invalid.toString()),
                                        List.of(invalid.toString(), gap.toString()),
                                        List.of(both.toString()))) {
            String[] args = new String[run.size() + 2];
            args[0] = "validate";
            args[1] = schema.toString();
            for (int i = 0; i < run.size(); i++) {
                args[i + 2] = run.get(i);
            }
            captureStderr(() -> {
                String out = captureStdout(() -> assertEquals(70, TsonCli.run(args), run::toString));
                assertTrue(out.contains("NOT_IMPLEMENTED"), () -> run + " -> " + out);
                assertTrue(out.contains("ATOM_CONSTRAINT_VIOLATION"),
                        () -> "the ordinary error still got its verdict: " + run + " -> " + out);
            });
        }
    }

    /**
     * <b>An author's schema error exits 1, and never 70.</b> Exit 70 prints "a gap in tson, not a problem
     * with your document" -- a false verdict for a construct the spec itself refuses, and one that sends the
     * author to file a bug instead of fixing their schema. Two that used to land there, both refused by
     * [TSON-SCHEMA]: a refinement body that is not a braced record (§12.1's {@code atom-refinement} takes a
     * {@code record-def}), now caught a phase earlier still by the parser, and an atom refinement that
     * widens rather than narrows (§5.7).
     */
    @Test
    void aSchemaErrorTheSpecRefusesExitsOneNotSeventy(@TempDir Path dir) throws IOException {
        for (String body : List.of("{ widens => !uint8 ^ { min: -10 } }", "{ narrow => !uint8 ^ 5 }")) {
            Path schema = writeFile(dir, "authorerror.tn", """
                    !!id:"https://example.test/cli-author-error.tn"
                    !!meta:"https://tson.io/2026/34/m/meta.tn"
                    !!import:"https://tson.io/2026/34/m/core.tn"
                    %s
                    """.formatted(body));

            String err = captureStderr(() -> {
                String out = captureStdout(() ->
                        assertEquals(1, TsonCli.run(new String[] {"compile", schema.toString()}), body));
                assertFalse(out.contains("NOT_IMPLEMENTED"), out);
                assertFalse(out.contains("Please report it"), out);
            });
            assertFalse(err.contains("a gap in tson"), err);
        }
    }

    /**
     * Naming a template as a data document's own type is the author's error, and gets an author's answer:
     * exit 1 and a diagnostic naming the route. It used to reach an ErrorReader and exit 70 under "this is a
     * bug in tson", the worst answer in the whole surface for one of the likeliest mistakes.
     */
    @Test
    void dataNamingATemplateIsAnOrdinaryVerdict(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "paged.tn", """
                !!id:"https://example.test/cli-paged.tn"
                !!meta:"https://tson.io/2026/34/m/meta.tn"
                !!import:"https://tson.io/2026/34/m/core.tn"
                {
                  order => { id: text }
                  paged => <T> { items: [T] }
                  orders_page => paged<order>
                }
                """);
        Path data = writeFile(dir, "paged-data.tn", """
                !!schema:"https://example.test/cli-paged.tn"
                !paged { items: [ { id: "a" } ] }
                """);

        String out = captureStdout(() ->
                assertEquals(1, TsonCli.run(new String[] {"validate", schema.toString(), data.toString()})));

        assertTrue(out.contains("is a template taking 1 type argument [T]"), out);
        assertTrue(out.contains("my_type => paged<...>"), out);
    }

    @Test
    void helpExitsZeroToStdout() throws IOException {
        for (String flag : new String[] {"--help", "-h", "help"}) {
            String out = captureStdout(() -> assertEquals(0, TsonCli.run(new String[] {flag})));
            assertTrue(out.contains("usage:"), flag + " => " + out);
        }
    }

    @Test
    void perCommandHelpExitsZeroToStdout() throws IOException {
        String validate = captureStdout(() -> assertEquals(0, TsonCli.run(new String[] {"validate", "--help"})));
        assertTrue(validate.contains("tson validate"), validate);

        String compile = captureStdout(() -> assertEquals(0, TsonCli.run(new String[] {"compile", "-h"})));
        assertTrue(compile.contains("tson compile"), compile);

        String policy = captureStdout(() -> assertEquals(0, TsonCli.run(new String[] {"policy", "--help"})));
        assertTrue(policy.contains("tson policy"), policy);
    }

    /**
     * <b>{@code tson policy} answers with no document in hand</b>, which is the whole of why it exists: a
     * generator that reads the [TSON-DATA] §8.2 policy before it writes never writes the name that would be
     * refused, where one that learns it from a refusal has already spent a round trip. Exit 0 always -- the
     * question is about this processor, and it has an answer whatever the state of anyone's documents.
     */
    @Test
    void policyPrintsTheUnicodePolicyThisBuildApplies() throws IOException {
        String text = captureStdout(() -> assertEquals(0, TsonCli.run(new String[] {"policy"})));
        assertTrue(text.contains("names:   HIGHLY_RESTRICTIVE"), text);
        assertTrue(text.contains("tokens:  UNRESTRICTED"), text);
        assertTrue(text.contains("unicode: " + TsonUnicodePolicy.dataVersion()), text);

        String json = captureStdout(() ->
                assertEquals(0, TsonCli.run(new String[] {"policy", "--output", "json"})));
        assertTrue(json.strip().startsWith("{\"names\":{\"level\":\"HIGHLY_RESTRICTIVE\""), json);
        assertTrue(json.contains("\"unicodeDataVersion\":\"" + TsonUnicodePolicy.dataVersion() + "\""), json);
    }

    /** A stray argument is a usage error, the same as anywhere else -- this command takes only {@code --output}. */
    @Test
    void policyRejectsAPositionalArgument() throws IOException {
        String err = captureStderr(() -> assertEquals(2, TsonCli.run(new String[] {"policy", "please"})));
        assertTrue(err.contains("tson policy"), err);
    }

    @Test
    void plainDataWithNoSchemaValidatesSchemalessly(@TempDir Path dir) throws IOException {
        // A data file with no !!schema is checked schemalessly (base syntax + built-in atoms), even
        // when schema files are also present. A plain, well-formed value is valid.
        Path schema = writeFile(dir, "schema.tn1", """
                !!id:"https://example.test/cli-arg-test.tn1"
                !!meta:"https://tson.io/2026/34/m/meta.tn"
                !!import:"https://tson.io/2026/34/m/core.tn"
                { my_int => int32 }
                """);
        Path data = writeFile(dir, "data.tson", "42");

        String out = captureStdout(() ->
                assertEquals(0, TsonCli.run(new String[] {"validate", schema.toString(), data.toString()})));

        assertTrue(out.contains("OK"), out);
    }

    @Test
    void validateEndToEndThroughMainDispatchExitsZeroForValidData(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "schema.tn1", """
                !!id:"https://example.test/cli-arg-test-2.tn1"
                !!meta:"https://tson.io/2026/34/m/meta.tn"
                !!import:"https://tson.io/2026/34/m/core.tn"
                { my_int => int32 }
                """);
        Path data = writeFile(dir, "data.tson", """
                !!schema:"https://example.test/cli-arg-test-2.tn1"
                !my_int 42
                """);

        int exitCode = TsonCli.run(new String[] {
                "validate", "--output", "json", schema.toString(), data.toString()});

        assertEquals(0, exitCode);
    }

    /** {@code -} is standard input all the way through {@code main}'s own dispatch, not just in the command. */
    @Test
    void dashReadsOneDataDocumentFromStandardInput(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "schema.tn1", """
                !!id:"https://example.test/cli-stdin.tn1"
                !!meta:"https://tson.io/2026/34/m/meta.tn"
                !!import:"https://tson.io/2026/34/m/core.tn"
                { my_int => int32 }
                """);
        String data = "!!schema:\"https://example.test/cli-stdin.tn1\"\n!my_int 42\n";

        String out = withStdin(data, () -> captureStdout(() -> assertEquals(0,
                TsonCli.run(new String[] {"validate", "--output", "json", schema.toString(), "-"}))));

        assertTrue(out.contains("\"file\":\"-\",\"valid\":true"), out);
    }

    /**
     * There is one standard input and the first read consumes it, so a second {@code -} could only ever
     * report an empty document -- which would come back valid, the worst available answer.
     */
    @Test
    void asecondDashIsAUsageError() throws IOException {
        String err = captureStderr(() ->
                assertEquals(2, TsonCli.run(new String[] {"validate", "-", "-"})));

        assertTrue(err.contains("standard input can only be read once"), err);
    }

    /**
     * Only the exact argument {@code -} is standard input. A file genuinely named {@code -} stays
     * reachable by any path that spells more than the bare dash -- {@code ./-} being the usual Unix
     * escape hatch -- so the convention costs nothing.
     */
    @Test
    void onlyABareDashIsStandardInputNotAPathEndingInOne(@TempDir Path dir) throws IOException {
        writeFile(dir, "-", "42");

        assertEquals(0, TsonCli.run(new String[] {"validate", dir.resolve("-").toString()}));
    }

    @Test
    void initScaffoldsAnExampleThatActuallyValidates(@TempDir Path dir) {
        assertEquals(0, TsonCli.run(new String[] {"init-example", dir.toString()}));

        Path schema = dir.resolve("person.tn");
        Path data = dir.resolve("person-data.tn");
        assertTrue(Files.exists(schema), "person.tn written");
        assertTrue(Files.exists(data), "person-data.tn written");

        // The whole point: the scaffolded pair the README's getting-started walks through must
        // validate cleanly, so onboarding can never ship a broken example. The data is
        // self-describing (!!schema + a root !person), so no --type is needed.
        assertEquals(0, TsonCli.run(new String[] {
                "validate", schema.toString(), data.toString()}));
    }

    /**
     * Naming a directory that does not exist yet is how a scaffolding command is normally used. It used to
     * escape as an {@code UncheckedIOException}, so the very first command a newcomer runs printed a stack
     * trace under "this is a bug in tson" and exited 70 -- the code reserved for a fault in the library.
     */
    @Test
    void initCreatesATargetDirectoryThatDoesNotExistYet(@TempDir Path dir) {
        Path fresh = dir.resolve("brand").resolve("new");

        assertEquals(0, TsonCli.run(new String[] {"init-example", fresh.toString()}));

        assertTrue(Files.exists(fresh.resolve("person.tn")), "person.tn written into a created directory");
        assertEquals(0, TsonCli.run(new String[] {
                "validate", fresh.resolve("person.tn").toString(), fresh.resolve("person-data.tn").toString()}));
    }

    /** An unwritable target is the user's problem, so it is exit 1 with a message -- never the library-fault code. */
    @Test
    void initReportsAnUnwritableTargetAsExitOne(@TempDir Path dir) throws IOException {
        Path takenByAFile = dir.resolve("not-a-directory");
        Files.writeString(takenByAFile, "");

        String err = captureStderr(() ->
                assertEquals(1, TsonCli.run(new String[] {"init-example", takenByAFile.toString()})));

        assertTrue(err.contains("could not write the example files"), err);
    }

    @Test
    void initRefusesToOverwriteExistingFiles(@TempDir Path dir) throws IOException {
        assertEquals(0, TsonCli.run(new String[] {"init-example", dir.toString()}));
        String err = captureStderr(() -> assertEquals(1, TsonCli.run(new String[] {"init-example", dir.toString()})));
        assertTrue(err.contains("refusing to overwrite"), err);
    }

    private static Path writeFile(Path dir, String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    private interface ThrowingRunnable {
        void run() throws IOException;
    }

    private interface ThrowingSupplier<T> {
        T get() throws IOException;
    }

    /** Runs {@code body} with {@code content} on standard input, restoring the real stream afterwards. */
    private static <T> T withStdin(String content, ThrowingSupplier<T> body) throws IOException {
        InputStream original = System.in;
        System.setIn(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        try {
            return body.get();
        } finally {
            System.setIn(original);
        }
    }

    private static String captureStderr(ThrowingRunnable body) throws IOException {
        PrintStream original = System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            body.run();
        } finally {
            System.setErr(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static String captureStdout(ThrowingRunnable body) throws IOException {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            body.run();
        } finally {
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
