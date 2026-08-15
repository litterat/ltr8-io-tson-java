package io.ltr8.tson.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
            !!id:"https://example.test/cli-validate.tn1"
            !!meta:"https://tson.io/2026/32/m/meta.tn"
            !!import:"https://tson.io/2026/32/m/core.tn"
            {
              my_record => { a: int32  b: int32 }
            }
            """;

    private static String selfDescribing(String body) {
        return "!!schema:\"https://example.test/cli-validate.tn1\"\n!my_record " + body + "\n";
    }

    // --- schema-driven (data carries !!schema) ---

    @Test
    void selfDescribingDataValidatesAgainstItsDeclaredSchema(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "schema.tn1", RECORD_SCHEMA);
        Path data = writeFile(dir, "data.tson", selfDescribing("{ a: 1  b: 2 }"));

        String output = captureStdout(() ->
                assertEquals(0, ValidateCommand.run(List.of(schema, data), OutputFormat.TEXT)));

        assertEquals("OK", output.strip());
    }

    @Test
    void fileOrderDoesNotMatter(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "schema.tn1", RECORD_SCHEMA);
        Path data = writeFile(dir, "data.tson", selfDescribing("{ a: 1  b: 2 }"));

        String output = captureStdout(() ->  // data listed before its schema
                assertEquals(0, ValidateCommand.run(List.of(data, schema), OutputFormat.TEXT)));

        assertEquals("OK", output.strip());
    }

    @Test
    void aFileWithMultipleProblemsReportsEveryOneOfThem(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "schema.tn1", RECORD_SCHEMA);
        // "b" missing, "a" out of int32 range -- two independent problems in one file.
        Path data = writeFile(dir, "data.tson", selfDescribing("{ a: 99999999999999 }"));

        String output = captureStdout(() ->
                assertEquals(1, ValidateCommand.run(List.of(schema, data), OutputFormat.TEXT)));

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
        Path schema = writeFile(dir, "schema.tn1", RECORD_SCHEMA);
        Path data = writeFile(dir, "data.tson", selfDescribing("{ a: 1  b: 2  hallucinated_field: \"nope\" }"));

        String output = captureStdout(() ->
                assertEquals(1, ValidateCommand.run(List.of(schema, data), OutputFormat.TEXT)));

        assertTrue(output.contains("[UNRECOGNIZED_FIELD]"), output);
        assertTrue(output.contains("/hallucinated_field"), output);
        assertTrue(output.contains("(a | b)"), output);
    }

    @Test
    void aDeclaredSchemaThatWasNotProvidedIsASchemaError(@TempDir Path dir) throws IOException {
        // The data names a schema URI, but no schema file for it was given.
        Path data = writeFile(dir, "data.tson",
                "!!schema:\"https://example.test/not-provided.tn1\"\n!my_record { a: 1  b: 2 }\n");

        String output = captureStdout(() ->
                assertEquals(1, ValidateCommand.run(List.of(data), OutputFormat.TEXT)));

        assertTrue(output.contains("[SCHEMA_ERROR]"), output);
        assertTrue(output.contains("not-provided"), output);
    }

    @Test
    void aRootTypeRefTheSchemaDoesNotDeclareIsAnUnknownType(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "schema.tn1", RECORD_SCHEMA);
        Path data = writeFile(dir, "data.tson",
                "!!schema:\"https://example.test/cli-validate.tn1\"\n!no_such_type { a: 1  b: 2 }\n");

        String output = captureStdout(() ->
                assertEquals(1, ValidateCommand.run(List.of(schema, data), OutputFormat.TEXT)));

        assertTrue(output.contains("[UNKNOWN_TYPE]"), output);
    }

    @Test
    void twoSchemasAndTwoDataFilesEachPickTheirOwn(@TempDir Path dir) throws IOException {
        Path recordSchema = writeFile(dir, "record.tn1", RECORD_SCHEMA);
        Path pointSchema = writeFile(dir, "point.tn1", """
                !!id:"https://example.test/cli-point.tn1"
                !!meta:"https://tson.io/2026/32/m/meta.tn"
                !!import:"https://tson.io/2026/32/m/core.tn"
                { point => { x: int32  y: int32 } }
                """);
        Path recordData = writeFile(dir, "rec.tson", selfDescribing("{ a: 1  b: 2 }"));
        Path pointData = writeFile(dir, "pt.tson",
                "!!schema:\"https://example.test/cli-point.tn1\"\n!point { x: 3  y: 4 }\n");

        String output = captureStdout(() -> assertEquals(0,
                ValidateCommand.run(List.of(recordSchema, pointSchema, recordData, pointData), OutputFormat.TEXT)));

        assertTrue(output.contains("OK"), output);
    }

    // --- schemaless (data has no !!schema) ---

    @Test
    void plainDataWithGoodBuiltinAtomsValidatesSchemalessly(@TempDir Path dir) throws IOException {
        Path data = writeFile(dir, "data.tson", "{ id: !uuid 9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09  n: !int32 5 }");

        String output = captureStdout(() ->
                assertEquals(0, ValidateCommand.run(List.of(data), OutputFormat.TEXT)));

        assertEquals("OK", output.strip());
    }

    @Test
    void plainDataWithABadBuiltinAtomIsInvalidSchemalessly(@TempDir Path dir) throws IOException {
        Path data = writeFile(dir, "data.tson", "{ n: !int32 twelve }");

        String output = captureStdout(() ->
                assertEquals(1, ValidateCommand.run(List.of(data), OutputFormat.TEXT)));

        assertTrue(output.contains("[ATOM_CONSTRAINT_VIOLATION]"), output);
    }

    // --- infra ---

    @Test
    void onlySchemaFilesGivenIsAUsageError(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "schema.tn1", RECORD_SCHEMA);

        String output = captureStdout(() ->
                assertEquals(2, ValidateCommand.run(List.of(schema), OutputFormat.TEXT)));

        assertTrue(output.contains("no data files"), output);
    }

    @Test
    void jsonOutputIsWellShaped(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "schema.tn1", RECORD_SCHEMA);
        Path data = writeFile(dir, "data.tson", selfDescribing("{ a: 99999999999999  b: 2 }"));

        String output = captureStdout(() ->
                assertEquals(1, ValidateCommand.run(List.of(schema, data), OutputFormat.JSON)));

        assertTrue(output.contains("\"valid\":false"), output);
        assertTrue(output.contains("\"code\":\"ATOM_CONSTRAINT_VIOLATION\""), output);
    }

    /**
     * The whole point of the envelope: whatever the file count, {@code --output json} is one document a
     * harness can parse, with the filename each verdict belongs to <i>inside</i> it. The old shape put a
     * bare {@code # <file>} line between per-file objects, which is neither JSON nor JSONL.
     */
    @Test
    void multiFileJsonOutputIsOneParseableDocument(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "schema.tn1", RECORD_SCHEMA);
        Path good = writeFile(dir, "good.tson", selfDescribing("{ a: 1  b: 2 }"));
        Path bad = writeFile(dir, "bad.tson", selfDescribing("{ a: 1 }"));

        String output = captureStdout(() ->
                assertEquals(1, ValidateCommand.run(List.of(schema, good, bad), OutputFormat.JSON)));

        assertEquals(1, output.strip().lines().count(), output);
        assertFalse(output.contains("# "), output);
        assertTrue(output.startsWith("{\"valid\":false,\"files\":["), output);
        assertTrue(output.contains("\"file\":\"" + good + "\",\"valid\":true,\"errors\":[]"), output);
        assertTrue(output.contains("\"file\":\"" + bad + "\",\"valid\":false"), output);
        assertTrue(output.contains("\"code\":\"FIELD_REQUIRED\""), output);
    }

    /** A single file gets the same envelope, so a consumer never branches on file count. */
    @Test
    void aSingleFileGetsTheSameEnvelope(@TempDir Path dir) throws IOException {
        Path data = writeFile(dir, "data.tson", "42");

        String output = captureStdout(() ->
                assertEquals(0, ValidateCommand.run(List.of(data), OutputFormat.JSON)));

        assertEquals("{\"valid\":true,\"files\":[{\"file\":\"" + data + "\",\"valid\":true,\"errors\":[]}],"
                + "\"errors\":[]}", output.strip());
    }

    /**
     * A failure that stops the run before any document is read (here: no data files at all) keeps the
     * envelope too, with an empty {@code files} and the problem at run level -- so the exit-2 path
     * doesn't hand a machine consumer a second shape to parse.
     */
    @Test
    void aRunLevelFailureKeepsTheEnvelopeWithNoFilesInIt(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "schema.tn1", RECORD_SCHEMA);

        String output = captureStdout(() ->
                assertEquals(2, ValidateCommand.run(List.of(schema), OutputFormat.JSON)));

        assertTrue(output.strip().startsWith("{\"valid\":false,\"files\":[],\"errors\":[{"), output);
        assertTrue(output.contains("no data files"), output);
    }

    /** Text keeps the per-file header, and keeps printing it only when there is more than one file. */
    @Test
    void textStillLabelsEachFileWhenThereIsMoreThanOne(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "schema.tn1", RECORD_SCHEMA);
        Path good = writeFile(dir, "good.tson", selfDescribing("{ a: 1  b: 2 }"));
        Path bad = writeFile(dir, "bad.tson", selfDescribing("{ a: 1 }"));

        String output = captureStdout(() ->
                assertEquals(1, ValidateCommand.run(List.of(schema, good, bad), OutputFormat.TEXT)));

        assertTrue(output.contains("# " + good), output);
        assertTrue(output.contains("# " + bad), output);
        assertTrue(output.contains("[FIELD_REQUIRED]"), output);
    }

    @Test
    void aSchemaPinnedByItsOwnHashResolvesAgainstAPlainReference(@TempDir Path dir) throws IOException {
        // The schema's !!id carries a ?sha256= pin; the data's !!schema is plain. Matching is by
        // canonical identity (the hash is not identity), so it still validates.
        Path schema = writeFile(dir, "schema.tn1",
                "!!id:\"https://example.test/cli-validate.tn1?sha256=" + "a".repeat(64) + "\"\n"
                        + "!!meta:\"https://tson.io/2026/32/m/meta.tn\"\n"
                        + "!!import:\"https://tson.io/2026/32/m/core.tn\"\n"
                        + "{ my_record => { a: int32  b: int32 } }\n");
        Path data = writeFile(dir, "data.tson", selfDescribing("{ a: 1  b: 2 }"));

        String output = captureStdout(() ->
                assertEquals(0, ValidateCommand.run(List.of(schema, data), OutputFormat.TEXT)));

        assertEquals("OK", output.strip());
    }

    @Test
    void aFileClaimingABundledSchemaIdIsIgnoredWithANotice(@TempDir Path dir) throws IOException {
        // Overriding meta-kernel/meta/core isn't supported -- the file is skipped, not used, and a
        // note goes to stderr so it isn't silently dropped.
        Path fake = writeFile(dir, "core.tn", """
                !!id:"https://tson.io/2026/32/m/core.tn"
                !!meta:"https://tson.io/2026/32/m/meta.tn"
                { my_thing => int32 }
                """);
        Path data = writeFile(dir, "data.tson", "42");   // plain -> schemaless -> valid

        String err = captureStderr(() ->
                assertEquals(0, ValidateCommand.run(List.of(fake, data), OutputFormat.TEXT)));

        assertTrue(err.contains("not supported"), err);
        assertTrue(err.contains("core.tn"), err);
    }

    private static Path writeFile(Path dir, String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    private interface ThrowingRunnable {
        void run() throws IOException;
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
