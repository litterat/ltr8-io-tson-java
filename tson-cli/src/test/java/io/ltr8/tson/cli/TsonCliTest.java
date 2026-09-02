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
            !!meta:"https://tson.io/2026/35/m/meta.tn"
            !!import:"https://tson.io/2026/35/m/core.tn"
            {
              stamped => { at: dynamic  n: int32 }
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
        // Each rank alone, then beaten by the one above it: 70 > 78 > 69 > 75 > 1.
        assertEquals(70, TsonCli.exitCodeFor(List.of(Diagnostic.Code.NOT_IMPLEMENTED)));
        assertEquals(78, TsonCli.exitCodeFor(List.of(Diagnostic.Code.BIND_MISMATCH)));
        assertEquals(69, TsonCli.exitCodeFor(List.of(Diagnostic.Code.SCHEMA_NOT_PERMITTED)));
        assertEquals(75, TsonCli.exitCodeFor(List.of(Diagnostic.Code.SCHEMA_TIMEOUT)));
        assertEquals(1, TsonCli.exitCodeFor(List.of(Diagnostic.Code.SCHEMA_ERROR)));

        assertEquals(70, TsonCli.exitCodeFor(List.of(
                Diagnostic.Code.NOT_IMPLEMENTED, Diagnostic.Code.BIND_MISMATCH)));
        assertEquals(78, TsonCli.exitCodeFor(List.of(
                Diagnostic.Code.BIND_MISMATCH, Diagnostic.Code.SCHEMA_NOT_FOUND)));
        assertEquals(69, TsonCli.exitCodeFor(List.of(
                Diagnostic.Code.SCHEMA_NOT_FOUND, Diagnostic.Code.SCHEMA_TIMEOUT)));
        assertEquals(75, TsonCli.exitCodeFor(List.of(
                Diagnostic.Code.SCHEMA_UNREACHABLE, Diagnostic.Code.SCHEMA_ERROR)));

        // Every fetch code lands in one of the two ranks -- no reason falls through to "checked and rejected".
        for (io.ltr8.tson.compiler.TsonSchemaFetchException.Reason reason
                : io.ltr8.tson.compiler.TsonSchemaFetchException.Reason.values()) {
            int code = TsonCli.exitCodeFor(List.of(Diagnostic.Code.of(reason)));
            assertTrue(code == 69 || code == 75, () -> reason + " fell through to " + code);
        }
    }

    /**
     * <b>A gap at read time is a gap, end to end.</b> The schema loads clean and the document is well
     * formed; what cannot be done is check one field against it, so the run is entitled to no verdict on
     * that field. It rides in the report as {@code NOT_IMPLEMENTED} -- with the data path and position of
     * the value that could not be read, like any other read diagnostic -- and {@link TsonCli#exitCodeFor}
     * lifts the run to 70 with the note on stderr, so the report on stdout stays exactly what {@code
     * --output json|tson} promises.
     *
     * <p>Never exit 1, which would tell a script the document was judged and rejected. The scoped instances
     * reach this ({@code CLAUDE.md}); {@code dynamic} is the cheapest to write.
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
                    !!meta:"https://tson.io/2026/35/m/meta.tn"
                    !!import:"https://tson.io/2026/35/m/core.tn"
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
                !!meta:"https://tson.io/2026/35/m/meta.tn"
                !!import:"https://tson.io/2026/35/m/core.tn"
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
        for (String[] argv : new String[][] {{"init-example", "--help"}, {"validate", "--help"},
                {"compile", "-h"}, {"policy", "--help"}, {"hash", "--help"}}) {
            String out = captureStdout(() -> assertEquals(0, TsonCli.run(argv)));
            assertTrue(out.contains("usage: tson " + argv[0]), argv[0] + " => " + out);
        }
    }

    /**
     * <b>Help is two levels, and each carries what the other should not.</b> The top level lists the
     * commands; a command's own help carries its options -- including the [TSON-DATA] §8.2 policy block for
     * the three that judge a name, and not for the two that do not. Printing everything at the top made the
     * policy flags a wall of text in front of someone who only wanted to know what {@code hash} does.
     */
    @Test
    void policyFlagsAreDocumentedByTheCommandsThatTakeThemAndNoOthers() throws IOException {
        for (String command : new String[] {"validate", "compile", "policy"}) {
            String out = captureStdout(() -> assertEquals(0, TsonCli.run(new String[] {command, "--help"})));
            assertTrue(out.contains("--identifier-policy <level>"), command + " => " + out);
            assertTrue(out.contains("--token-scripts <A+B>"), command + " => " + out);
            assertTrue(out.contains("ascii-only, single-script"), command + " => " + out);
        }
        for (String command : new String[] {"hash", "init-example"}) {
            String out = captureStdout(() -> assertEquals(0, TsonCli.run(new String[] {command, "--help"})));
            assertFalse(out.contains("--identifier-policy"), command + " => " + out);
        }

        String top = captureStdout(() -> assertEquals(0, TsonCli.run(new String[] {"--help"})));
        assertFalse(top.contains("--identifier-policy"), top);
        assertTrue(top.contains("`tson <command> --help`"), top);
        for (String command : new String[] {"init-example", "validate", "compile", "policy", "hash"}) {
            assertTrue(top.contains("  " + command + " "), command + " missing from the command list: " + top);
        }
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
        assertTrue(text.contains("identifier policy: HIGHLY_RESTRICTIVE"), text);
        assertTrue(text.contains("token policy:      UNRESTRICTED"), text);
        assertTrue(text.contains("unicode data:      " + TsonUnicodePolicy.dataVersion()), text);

        String json = captureStdout(() ->
                assertEquals(0, TsonCli.run(new String[] {"policy", "--output", "json"})));
        assertTrue(json.strip().startsWith("{\"identifier_policy\":{\"level\":\"HIGHLY_RESTRICTIVE\""), json);
        assertTrue(json.contains("\"unicode_data_version\":\"" + TsonUnicodePolicy.dataVersion() + "\""), json);
    }

    /** A stray argument is a usage error, the same as anywhere else -- this command takes only {@code --output}. */
    @Test
    void policyRejectsAPositionalArgument() throws IOException {
        String err = captureStderr(() -> assertEquals(2, TsonCli.run(new String[] {"policy", "please"})));
        assertTrue(err.contains("tson policy"), err);
    }

    /**
     * <b>{@code tson policy} takes the policy flags too, so it is the dry run for the other two commands.</b>
     * Someone about to relax a [TSON-DATA] §8.2 rule across a CI job can see what the relaxation actually
     * produces before pointing it at a document, which is the difference between configuring a policy and
     * guessing at one.
     */
    @Test
    void policyPrintsWhatTheFlagsWouldApply() throws IOException {
        String out = captureStdout(() -> assertEquals(0, TsonCli.run(new String[] {
                "policy", "--identifier-policy", "moderately-restrictive", "--identifier-per-segment",
                "--token-scripts", "Latin+Greek"})));

        assertTrue(out.contains("identifier policy: MODERATELY_RESTRICTIVE per segment"), out);
        // The token list brought its own level: Unrestricted scans nothing, so the list would have been inert.
        assertTrue(out.contains("token policy:      SINGLE_SCRIPT permitting [GREEK+LATIN]"), out);
    }

    /**
     * <b>A relaxation on the command line actually changes a verdict</b>, end to end through the real
     * dispatch. A mixed-script <em>annotation</em> name is refused under the default Highly Restrictive
     * policy and admitted once the combination is named -- which is the whole point of the flags, and the
     * thing that regressed silently while the configured policy reached the schema end and not the read.
     *
     * <p>An annotation name rather than a field name, because a Class 1 field name is lexical rather than a
     * name (§2.5, §7.7) and faces only the look-alike rule; and rather than a type-ref, because an unknown
     * one is a diagnostic of its own and the relaxed run here has to come back clean.
     */
    @Test
    void anIdentifierScriptListAdmitsANameTheDefaultRefuses(@TempDir Path dir) throws IOException {
        // Cyrillic а (U+0430) between two Latin letters -- built from code points, never typed.
        String mixed = "p" + new String(Character.toChars(0x0430)) + "y";
        Path data = writeFile(dir, "mixed.tson", "@" + mixed + ":1 2");

        String refused = captureStdout(() -> assertEquals(1, TsonCli.run(new String[] {
                "validate", data.toString()})));
        assertTrue(refused.contains("[RESTRICTED_SCRIPT]"), refused);
        assertTrue(refused.contains("note: refused under identifier policy HIGHLY_RESTRICTIVE"), refused);

        String admitted = captureStdout(() -> assertEquals(0, TsonCli.run(new String[] {
                "validate", "--identifier-policy", "single-script",
                "--identifier-scripts", "Latin+Cyrillic", data.toString()})));
        assertTrue(admitted.contains("OK"), admitted);
        assertTrue(admitted.contains("note: judged under identifier policy SINGLE_SCRIPT"), admitted);
    }

    /** A flag whose combination configures nothing is a usage error, not a silent no-op. */
    @Test
    void aRelaxationThatWouldConfigureNothingIsAUsageError() throws IOException {
        String err = captureStderr(() -> assertEquals(2, TsonCli.run(new String[] {
                "policy", "--token-policy", "unrestricted", "--token-scripts", "Latin+Cyrillic"})));
        assertTrue(err.contains("configure nothing"), err);
    }

    @Test
    void plainDataWithNoSchemaValidatesSchemalessly(@TempDir Path dir) throws IOException {
        // A data file with no !!schema is checked schemalessly (base syntax + built-in atoms), even
        // when schema files are also present. A plain, well-formed value is valid.
        Path schema = writeFile(dir, "schema.tn", """
                !!id:"https://example.test/cli-arg-test.tn"
                !!meta:"https://tson.io/2026/35/m/meta.tn"
                !!import:"https://tson.io/2026/35/m/core.tn"
                { my_int => int32 }
                """);
        Path data = writeFile(dir, "data.tson", "42");

        String out = captureStdout(() ->
                assertEquals(0, TsonCli.run(new String[] {"validate", schema.toString(), data.toString()})));

        assertTrue(out.contains("OK"), out);
    }

    @Test
    void validateEndToEndThroughMainDispatchExitsZeroForValidData(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "schema.tn", """
                !!id:"https://example.test/cli-arg-test-2.tn"
                !!meta:"https://tson.io/2026/35/m/meta.tn"
                !!import:"https://tson.io/2026/35/m/core.tn"
                { my_int => int32 }
                """);
        Path data = writeFile(dir, "data.tson", """
                !!schema:"https://example.test/cli-arg-test-2.tn"
                !my_int 42
                """);

        int exitCode = TsonCli.run(new String[] {
                "validate", "--output", "json", schema.toString(), data.toString()});

        assertEquals(0, exitCode);
    }

    /** {@code -} is standard input all the way through {@code main}'s own dispatch, not just in the command. */
    @Test
    void dashReadsOneDataDocumentFromStandardInput(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "schema.tn", """
                !!id:"https://example.test/cli-stdin.tn"
                !!meta:"https://tson.io/2026/35/m/meta.tn"
                !!import:"https://tson.io/2026/35/m/core.tn"
                { my_int => int32 }
                """);
        String data = "!!schema:\"https://example.test/cli-stdin.tn\"\n!my_int 42\n";

        String out = withStdin(data, () -> captureStdout(() -> assertEquals(0,
                TsonCli.run(new String[] {"validate", "--output", "json", schema.toString(), "-"}))));

        assertTrue(out.contains("\"file\":\"-\",\"outcome\":\"VALID\""), out);
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
