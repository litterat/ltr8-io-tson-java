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
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code validate} takes a flat list of files, auto-classifies each as schema or data, exposes the
 * schemas through a source, and validates each data file by resolving its own {@code !!schema}
 * (schema-driven) or, with no {@code !!schema}, schemalessly (base syntax + built-in atoms).
 */
class ValidateCommandTest {

    private static final String RECORD_SCHEMA = """
            !!id:"https://example.test/cli-validate.tn"
            !!meta:"https://tson.io/2026/35/m/meta.tn"
            !!import:"https://tson.io/2026/35/m/core.tn"
            {
              my_record => { a: int32  b: int32 }
            }
            """;

    private static String selfDescribing(String body) {
        return "!!schema:\"https://example.test/cli-validate.tn\"\n!my_record " + body + "\n";
    }

    // --- schema-driven (data carries !!schema) ---

    @Test
    void selfDescribingDataValidatesAgainstItsDeclaredSchema(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "schema.tn", RECORD_SCHEMA);
        Path data = writeFile(dir, "data.tson", selfDescribing("{ a: 1  b: 2 }"));

        String output = captureStdout(() ->
                assertEquals(0, ValidateCommand.run(inputs(schema, data), OutputFormat.TEXT, PolicyOptions.DEFAULTS)));

        assertEquals("OK", output.strip());
    }

    @Test
    void fileOrderDoesNotMatter(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "schema.tn", RECORD_SCHEMA);
        Path data = writeFile(dir, "data.tson", selfDescribing("{ a: 1  b: 2 }"));

        String output = captureStdout(() ->  // data listed before its schema
                assertEquals(0, ValidateCommand.run(inputs(data, schema), OutputFormat.TEXT, PolicyOptions.DEFAULTS)));

        assertEquals("OK", output.strip());
    }

    @Test
    void aFileWithMultipleProblemsReportsEveryOneOfThem(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "schema.tn", RECORD_SCHEMA);
        // "b" missing, "a" out of int32 range -- two independent problems in one file.
        Path data = writeFile(dir, "data.tson", selfDescribing("{ a: 99999999999999 }"));

        String output = captureStdout(() ->
                assertEquals(1, ValidateCommand.run(inputs(schema, data), OutputFormat.TEXT, PolicyOptions.DEFAULTS)));

        assertTrue(output.contains("[FIELD_REQUIRED]"), output);
        assertTrue(output.contains("[ATOM_CONSTRAINT_VIOLATION]"), output);
        assertTrue(output.contains("/a"), output);
        assertTrue(output.contains("/b"), output);
    }

    /**
     * A field the schema does not declare is a validation error, not a shrug ([TSON-SCHEMA] §7.2 -- records
     * are closed under their type). The exit code is what matters as much as the text: for a repair loop,
     * "we stored a field that does not exist" and "we rejected it" are opposite instructions.
     */
    @Test
    void aFieldTheSchemaDoesNotDeclareIsInvalid(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "schema.tn", RECORD_SCHEMA);
        Path data = writeFile(dir, "data.tson", selfDescribing("{ a: 1  b: 2  hallucinated_field: \"nope\" }"));

        String output = captureStdout(() ->
                assertEquals(1, ValidateCommand.run(inputs(schema, data), OutputFormat.TEXT, PolicyOptions.DEFAULTS)));

        assertTrue(output.contains("[UNRECOGNIZED_FIELD]"), output);
        assertTrue(output.contains("/hallucinated_field"), output);
        assertTrue(output.contains("(a | b)"), output);
    }

    /**
     * <b>69, not 1.</b> The data names a schema URI and no file for it was given, so this run never read
     * that schema -- it is in no position to say the document is invalid, and a script reading exit 1
     * would be told it had been judged and rejected. The forgotten file is the commonest way to reach
     * this, and it is the caller's setup at fault rather than either document.
     */
    @Test
    void aDeclaredSchemaThatWasNotProvidedIsUnavailableRatherThanAVerdict(@TempDir Path dir) throws IOException {
        Path data = writeFile(dir, "data.tson",
                "!!schema:\"https://example.test/not-provided.tn\"\n!my_record { a: 1  b: 2 }\n");

        String err = captureStderr(() -> {
            String output = captureStdout(() ->
                    assertEquals(69, ValidateCommand.run(inputs(data), OutputFormat.TEXT, PolicyOptions.DEFAULTS)));
            assertTrue(output.contains("[SCHEMA_NOT_FOUND]"), output);
            assertTrue(output.contains("not-provided"), output);
        });

        assertTrue(err.contains("could not be obtained"), err);
    }

    /**
     * The mismatch an author actually hits: the right file passed, but its {@code !!id} isn't the identity
     * the data names. Matching is by embedded identity, never by filename (§2.2.1), so the bare "no schema
     * file provided" reads as though the file were missing while they are looking straight at it. Listing
     * what the supplied files declare puts the two strings side by side.
     */
    @Test
    void anUnmatchedSchemaSaysWhatTheSuppliedFilesDeclare(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "schema.tn", RECORD_SCHEMA);   // declares .../cli-validate.tn
        Path data = writeFile(dir, "data.tson",
                "!!schema:\"https://example.test/typo.tn\"\n!my_record { a: 1  b: 2 }\n");

        String output = captureStdout(() ->
                assertEquals(69, ValidateCommand.run(inputs(schema, data), OutputFormat.TEXT, PolicyOptions.DEFAULTS)));

        assertTrue(output.contains("cannot fetch schema 'https://example.test/typo.tn'"), output);
        assertTrue(output.contains("no schema file on the command line declares that !!id"), output);
        assertTrue(output.contains("the schema files given declare: https://example.test/cli-validate.tn"),
                output);
    }

    /** With no schema files at all, the message says that rather than listing an empty set. */
    @Test
    void anUnmatchedSchemaWithNoSchemaFilesSaysSo(@TempDir Path dir) throws IOException {
        Path data = writeFile(dir, "data.tson",
                "!!schema:\"https://example.test/absent.tn\"\n!my_record { a: 1 }\n");

        String output = captureStdout(() ->
                assertEquals(69, ValidateCommand.run(inputs(data), OutputFormat.TEXT, PolicyOptions.DEFAULTS)));

        assertTrue(output.contains("no schema files were given"), output);
    }

    @Test
    void aRootTypeRefTheSchemaDoesNotDeclareIsAnUnknownType(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "schema.tn", RECORD_SCHEMA);
        Path data = writeFile(dir, "data.tson",
                "!!schema:\"https://example.test/cli-validate.tn\"\n!no_such_type { a: 1  b: 2 }\n");

        String output = captureStdout(() ->
                assertEquals(1, ValidateCommand.run(inputs(schema, data), OutputFormat.TEXT, PolicyOptions.DEFAULTS)));

        assertTrue(output.contains("[UNKNOWN_TYPE]"), output);
    }

    @Test
    void twoSchemasAndTwoDataFilesEachPickTheirOwn(@TempDir Path dir) throws IOException {
        Path recordSchema = writeFile(dir, "record.tn", RECORD_SCHEMA);
        Path pointSchema = writeFile(dir, "point.tn", """
                !!id:"https://example.test/cli-point.tn"
                !!meta:"https://tson.io/2026/35/m/meta.tn"
                !!import:"https://tson.io/2026/35/m/core.tn"
                { point => { x: int32  y: int32 } }
                """);
        Path recordData = writeFile(dir, "rec.tson", selfDescribing("{ a: 1  b: 2 }"));
        Path pointData = writeFile(dir, "pt.tson",
                "!!schema:\"https://example.test/cli-point.tn\"\n!point { x: 3  y: 4 }\n");

        String output = captureStdout(() -> assertEquals(0,
                ValidateCommand.run(inputs(recordSchema, pointSchema, recordData, pointData),
                        OutputFormat.TEXT, PolicyOptions.DEFAULTS)));

        assertTrue(output.contains("OK"), output);
    }

    // --- schemaless (data has no !!schema) ---

    @Test
    void plainDataWithGoodBuiltinAtomsValidatesSchemalessly(@TempDir Path dir) throws IOException {
        Path data = writeFile(dir, "data.tson", "{ id: !uuid 9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09  n: !int32 5 }");

        String output = captureStdout(() ->
                assertEquals(0, ValidateCommand.run(inputs(data), OutputFormat.TEXT, PolicyOptions.DEFAULTS)));

        assertEquals("OK", output.strip());
    }

    @Test
    void plainDataWithABadBuiltinAtomIsInvalidSchemalessly(@TempDir Path dir) throws IOException {
        Path data = writeFile(dir, "data.tson", "{ n: !int32 twelve }");

        String output = captureStdout(() ->
                assertEquals(1, ValidateCommand.run(inputs(data), OutputFormat.TEXT, PolicyOptions.DEFAULTS)));

        assertTrue(output.contains("[ATOM_CONSTRAINT_VIOLATION]"), output);
    }

    // --- infra ---

    @Test
    void onlySchemaFilesGivenIsAUsageError(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "schema.tn", RECORD_SCHEMA);

        String output = captureStdout(() ->
                assertEquals(2, ValidateCommand.run(inputs(schema), OutputFormat.TEXT, PolicyOptions.DEFAULTS)));

        assertTrue(output.contains("no data files"), output);
    }

    @Test
    void jsonOutputIsWellShaped(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "schema.tn", RECORD_SCHEMA);
        Path data = writeFile(dir, "data.tson", selfDescribing("{ a: 99999999999999  b: 2 }"));

        String output = captureStdout(() ->
                assertEquals(1, ValidateCommand.run(inputs(schema, data), OutputFormat.JSON, PolicyOptions.DEFAULTS)));

        assertTrue(output.contains("\"outcome\":\"INVALID\""), output);
        assertTrue(output.contains("\"code\":\"ATOM_CONSTRAINT_VIOLATION\""), output);
    }

    /**
     * The whole point of the envelope: whatever the file count, {@code --output json} is one document a
     * harness can parse, with the filename each verdict belongs to <i>inside</i> it. The old shape put a
     * bare {@code # <file>} line between per-file objects, which is neither JSON nor JSONL.
     */
    @Test
    void multiFileJsonOutputIsOneParseableDocument(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "schema.tn", RECORD_SCHEMA);
        Path good = writeFile(dir, "good.tson", selfDescribing("{ a: 1  b: 2 }"));
        Path bad = writeFile(dir, "bad.tson", selfDescribing("{ a: 1 }"));

        String output = captureStdout(() ->
                assertEquals(1, ValidateCommand.run(inputs(schema, good, bad), OutputFormat.JSON, PolicyOptions.DEFAULTS)));

        assertEquals(1, output.strip().lines().count(), output);
        assertFalse(output.contains("# "), output);
        assertTrue(output.startsWith("{\"outcome\":\"INVALID\",\"policy\":"), output);
        assertTrue(output.contains(",\"files\":["), output);
        assertTrue(output.contains("\"file\":\"" + good + "\",\"outcome\":\"VALID\",\"errors\":[]"), output);
        assertTrue(output.contains("\"file\":\"" + bad + "\",\"outcome\":\"INVALID\""), output);
        assertTrue(output.contains("\"code\":\"FIELD_REQUIRED\""), output);
    }

    /** A single file gets the same envelope, so a consumer never branches on file count. */
    @Test
    void aSingleFileGetsTheSameEnvelope(@TempDir Path dir) throws IOException {
        Path data = writeFile(dir, "data.tson", "42");

        String output = captureStdout(() ->
                assertEquals(0, ValidateCommand.run(inputs(data), OutputFormat.JSON, PolicyOptions.DEFAULTS)));

        // The policy is stated between the verdict and the files, once for the run: [TSON-DATA] §8.2's
        // rules are this deployment's configuration, and every envelope carries them whether or not it
        // refused anything.
        assertTrue(output.strip().startsWith("{\"outcome\":\"VALID\",\"policy\":{\"identifier_policy\":"), output);
        assertTrue(output.contains(",\"files\":[{\"file\":\"" + data + "\",\"outcome\":\"VALID\",\"errors\":[]}],"
                + "\"errors\":[]}"), output);
    }

    /**
     * A failure that stops the run before any document is read (here: no data files at all) keeps the
     * envelope too, with an empty {@code files} and the problem at run level -- so the exit-2 path
     * doesn't hand a machine consumer a second shape to parse.
     */
    @Test
    void aRunLevelFailureKeepsTheEnvelopeWithNoFilesInIt(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "schema.tn", RECORD_SCHEMA);

        String output = captureStdout(() ->
                assertEquals(2, ValidateCommand.run(inputs(schema), OutputFormat.JSON, PolicyOptions.DEFAULTS)));

        assertTrue(output.strip().startsWith("{\"outcome\":\"NOT_CHECKED\",\"policy\":"), output);
        assertTrue(output.contains(",\"files\":[],\"errors\":[{"), output);
        assertTrue(output.contains("no data files"), output);
    }

    /** Text keeps the per-file header, and keeps printing it only when there is more than one file. */
    @Test
    void textStillLabelsEachFileWhenThereIsMoreThanOne(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "schema.tn", RECORD_SCHEMA);
        Path good = writeFile(dir, "good.tson", selfDescribing("{ a: 1  b: 2 }"));
        Path bad = writeFile(dir, "bad.tson", selfDescribing("{ a: 1 }"));

        String output = captureStdout(() ->
                assertEquals(1, ValidateCommand.run(inputs(schema, good, bad), OutputFormat.TEXT, PolicyOptions.DEFAULTS)));

        assertTrue(output.contains("# " + good), output);
        assertTrue(output.contains("# " + bad), output);
        assertTrue(output.contains("[FIELD_REQUIRED]"), output);
    }

    @Test
    void aSchemaPinnedByItsOwnHashResolvesAgainstAPlainReference(@TempDir Path dir) throws IOException {
        // The schema's !!id carries a ?sha256= pin; the data's !!schema is plain. Matching is by
        // canonical identity (the hash is not identity), so it still validates.
        Path schema = writeFile(dir, "schema.tn",
                "!!id:\"https://example.test/cli-validate.tn?sha256=" + "a".repeat(64) + "\"\n"
                        + "!!meta:\"https://tson.io/2026/35/m/meta.tn\"\n"
                        + "!!import:\"https://tson.io/2026/35/m/core.tn\"\n"
                        + "{ my_record => { a: int32  b: int32 } }\n");
        Path data = writeFile(dir, "data.tson", selfDescribing("{ a: 1  b: 2 }"));

        String output = captureStdout(() ->
                assertEquals(0, ValidateCommand.run(inputs(schema, data), OutputFormat.TEXT, PolicyOptions.DEFAULTS)));

        assertEquals("OK", output.strip());
    }

    @Test
    void aFileClaimingABundledSchemaIdIsIgnoredWithANotice(@TempDir Path dir) throws IOException {
        // Overriding meta-kernel/meta/core isn't supported -- the file is skipped, not used, and a
        // note goes to stderr so it isn't silently dropped.
        Path fake = writeFile(dir, "core.tn", """
                !!id:"https://tson.io/2026/35/m/core.tn"
                !!meta:"https://tson.io/2026/35/m/meta.tn"
                { my_thing => int32 }
                """);
        Path data = writeFile(dir, "data.tson", "42");   // plain -> schemaless -> valid

        String err = captureStderr(() ->
                assertEquals(0, ValidateCommand.run(inputs(fake, data), OutputFormat.TEXT, PolicyOptions.DEFAULTS)));

        assertTrue(err.contains("not supported"), err);
        assertTrue(err.contains("core.tn"), err);
    }

    // --- standard input ---

    /**
     * The case the whole feature exists for: a harness holding a candidate document in memory pipes it
     * straight in, with the schema still an ordinary file, instead of writing a temp file per attempt.
     */
    @Test
    void aDataDocumentPipedInValidatesAgainstASchemaFile(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "schema.tn", RECORD_SCHEMA);

        String output = withStdin(selfDescribing("{ a: 1  b: 2 }"), () -> captureStdout(() ->
                assertEquals(0, ValidateCommand.run(
                        List.of(new ValidateInput.OfFile(schema), new ValidateInput.OfStdin()),
                        OutputFormat.TEXT, PolicyOptions.DEFAULTS))));

        assertEquals("OK", output.strip());
    }

    @Test
    void aPipedDocumentThatDoesNotValidateReportsUnderTheNameDash(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "schema.tn", RECORD_SCHEMA);

        String output = withStdin(selfDescribing("{ a: 1 }"), () -> captureStdout(() ->
                assertEquals(1, ValidateCommand.run(
                        List.of(new ValidateInput.OfFile(schema), new ValidateInput.OfStdin()),
                        OutputFormat.JSON, PolicyOptions.DEFAULTS))));

        assertTrue(output.contains("\"file\":\"-\",\"outcome\":\"INVALID\""), output);
        assertTrue(output.contains("\"code\":\"FIELD_REQUIRED\""), output);
    }

    /** Piped data is never classified, so a schema arriving on stdin is read as data and fails as data. */
    @Test
    void aSchemaPipedInIsTreatedAsDataNotAsASchema() throws IOException {
        String output = withStdin(RECORD_SCHEMA, () -> captureStdout(() ->
                assertEquals(1, ValidateCommand.run(List.of(new ValidateInput.OfStdin()),
                        OutputFormat.TEXT, PolicyOptions.DEFAULTS))));

        assertFalse(output.contains("no data files"), output);
        assertTrue(output.contains("["), output);   // a diagnostic, not a silent pass
    }

    /** Stdin on its own is a complete invocation -- no schema files needed for a schemaless check. */
    @Test
    void stdinAloneIsAValidInvocation() throws IOException {
        String output = withStdin("{ n: !int32 5 }", () -> captureStdout(() ->
                assertEquals(0, ValidateCommand.run(List.of(new ValidateInput.OfStdin()),
                        OutputFormat.TEXT, PolicyOptions.DEFAULTS))));

        assertEquals("OK", output.strip());
    }

    // --- unreadable files ---

    /**
     * The message names the failure, not the path twice. {@code NoSuchFileException}'s own message is
     * just the filename, so {@code "cannot read " + file + ": " + e.getMessage()} used to render as
     * {@code cannot read /x: /x}.
     */
    @Test
    void anUnreadableFileSaysWhyNotJustItsNameAgain(@TempDir Path dir) throws IOException {
        Path missing = dir.resolve("not-here.tn");

        String output = captureStdout(() ->
                assertEquals(2, ValidateCommand.run(inputs(missing), OutputFormat.TEXT, PolicyOptions.DEFAULTS)));

        assertTrue(output.contains("no such file"), output);
        assertFalse(output.contains(missing + ": " + missing), output);
    }

    private static List<ValidateInput> inputs(Path... files) {
        return Arrays.stream(files).<ValidateInput>map(ValidateInput.OfFile::new).toList();
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
}
