package io.ltr8.tson.cli;

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

    /**
     * The same routing end to end, through a schema that really reaches a gap: [TSON-SCHEMA] §8.1 gives a
     * type parameter inside a choice no open representation, and the refusal's own message names the way to
     * write it today. Exit 70 stays -- a gap is not a verdict on the schema -- but it is now decided by the
     * diagnostic's own code rather than by an exception that had to destroy the pass to be seen, so the gap
     * arrives located, in the report, like every other problem.
     */
    @Test
    void aSchemaReachingAGapCompilesToSeventyWithTheGapsOwnMessage(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "gap.tn", """
                !!id:"https://example.test/cli-gap.tn"
                !!meta:"https://tson.io/2026/33/m/meta.tn"
                !!import:"https://tson.io/2026/33/m/core.tn"
                { boxed => <T> { v: (T | text) } }
                """);

        String out = captureStdout(() ->
                assertEquals(70, TsonCli.run(new String[] {"compile", schema.toString()})));

        assertTrue(out.contains("[NOT_IMPLEMENTED] /boxed"), out);
        assertTrue(out.contains("is the way to write this today"), out);
        assertFalse(out.contains("Please report it"), out);
    }

    /**
     * The point of routing a gap through the report rather than through an exception: a schema with a gap
     * <em>and</em> an ordinary error says both, in one pass. Thrown, the gap took {@code widens}'s verdict
     * with it and the author fixed one thing per run, learning about the next only after the first was
     * gone.
     *
     * <p>The run is still 70, not 1, and the two codes are why: {@code widens} really is invalid, but
     * something here was not checked at all, so "invalid" is not a verdict this run is entitled to give.
     */
    @Test
    void aSchemaWithBothAGapAndAnOrdinaryErrorReportsBoth(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "mixed.tn", """
                !!id:"https://example.test/cli-mixed.tn"
                !!meta:"https://tson.io/2026/33/m/meta.tn"
                !!import:"https://tson.io/2026/33/m/core.tn"
                {
                  boxed  => <T> { v: (T | text) }
                  widens => !uint8 ^ { min: -10 }
                  fine   => int32
                }
                """);

        String out = captureStdout(() ->
                assertEquals(70, TsonCli.run(new String[] {"compile", schema.toString()})));

        assertTrue(out.contains("[NOT_IMPLEMENTED] /boxed"), out);
        assertTrue(out.contains("[SCHEMA_ERROR] /widens"), out);
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
                !!meta:"https://tson.io/2026/33/m/meta.tn"
                !!import:"https://tson.io/2026/33/m/core.tn"
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
    }

    @Test
    void plainDataWithNoSchemaValidatesSchemalessly(@TempDir Path dir) throws IOException {
        // A data file with no !!schema is checked schemalessly (base syntax + built-in atoms), even
        // when schema files are also present. A plain, well-formed value is valid.
        Path schema = writeFile(dir, "schema.tn1", """
                !!id:"https://example.test/cli-arg-test.tn1"
                !!meta:"https://tson.io/2026/33/m/meta.tn"
                !!import:"https://tson.io/2026/33/m/core.tn"
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
                !!meta:"https://tson.io/2026/33/m/meta.tn"
                !!import:"https://tson.io/2026/33/m/core.tn"
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
                !!meta:"https://tson.io/2026/33/m/meta.tn"
                !!import:"https://tson.io/2026/33/m/core.tn"
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
