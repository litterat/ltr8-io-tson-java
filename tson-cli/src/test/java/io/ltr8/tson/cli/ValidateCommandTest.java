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
                assertEquals(0, ValidateCommand.run(List.of(schema, data), null, OutputFormat.TEXT)));

        assertEquals("OK", output.strip());
    }

    @Test
    void fileOrderDoesNotMatter(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "schema.tn1", RECORD_SCHEMA);
        Path data = writeFile(dir, "data.tson", selfDescribing("{ a: 1  b: 2 }"));

        String output = captureStdout(() ->  // data listed before its schema
                assertEquals(0, ValidateCommand.run(List.of(data, schema), null, OutputFormat.TEXT)));

        assertEquals("OK", output.strip());
    }

    @Test
    void aFileWithMultipleProblemsReportsEveryOneOfThem(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "schema.tn1", RECORD_SCHEMA);
        // "b" missing, "a" out of int32 range -- two independent problems in one file.
        Path data = writeFile(dir, "data.tson", selfDescribing("{ a: 99999999999999 }"));

        String output = captureStdout(() ->
                assertEquals(1, ValidateCommand.run(List.of(schema, data), null, OutputFormat.TEXT)));

        assertTrue(output.contains("[FIELD_REQUIRED]"), output);
        assertTrue(output.contains("[ATOM_CONSTRAINT_VIOLATION]"), output);
        assertTrue(output.contains("/a"), output);
        assertTrue(output.contains("/b"), output);
    }

    @Test
    void anExplicitTypeOverridesTheRootTypeRef(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "schema.tn1", RECORD_SCHEMA);
        Path data = writeFile(dir, "data.tson", selfDescribing("{ a: 1  b: 2 }"));

        String output = captureStdout(() ->
                assertEquals(0, ValidateCommand.run(List.of(schema, data), "my_record", OutputFormat.TEXT)));

        assertEquals("OK", output.strip());
    }

    @Test
    void aDeclaredSchemaThatWasNotProvidedIsASchemaError(@TempDir Path dir) throws IOException {
        // The data names a schema URI, but no schema file for it was given.
        Path data = writeFile(dir, "data.tson",
                "!!schema:\"https://example.test/not-provided.tn1\"\n!my_record { a: 1  b: 2 }\n");

        String output = captureStdout(() ->
                assertEquals(1, ValidateCommand.run(List.of(data), null, OutputFormat.TEXT)));

        assertTrue(output.contains("[SCHEMA_ERROR]"), output);
        assertTrue(output.contains("not-provided"), output);
    }

    @Test
    void anUnknownTypeNameIsReported(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "schema.tn1", RECORD_SCHEMA);
        Path data = writeFile(dir, "data.tson", selfDescribing("{ a: 1  b: 2 }"));

        String output = captureStdout(() ->
                assertEquals(1, ValidateCommand.run(List.of(schema, data), "does_not_exist", OutputFormat.TEXT)));

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
                ValidateCommand.run(List.of(recordSchema, pointSchema, recordData, pointData), null, OutputFormat.TEXT)));

        assertTrue(output.contains("OK"), output);
    }

    // --- schemaless (data has no !!schema) ---

    @Test
    void plainDataWithGoodBuiltinAtomsValidatesSchemalessly(@TempDir Path dir) throws IOException {
        Path data = writeFile(dir, "data.tson", "{ id: !uuid 9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09  n: !int32 5 }");

        String output = captureStdout(() ->
                assertEquals(0, ValidateCommand.run(List.of(data), null, OutputFormat.TEXT)));

        assertEquals("OK", output.strip());
    }

    @Test
    void plainDataWithABadBuiltinAtomIsInvalidSchemalessly(@TempDir Path dir) throws IOException {
        Path data = writeFile(dir, "data.tson", "{ n: !int32 twelve }");

        String output = captureStdout(() ->
                assertEquals(1, ValidateCommand.run(List.of(data), null, OutputFormat.TEXT)));

        assertTrue(output.contains("[ATOM_CONSTRAINT_VIOLATION]"), output);
    }

    // --- infra ---

    @Test
    void onlySchemaFilesGivenIsAUsageError(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "schema.tn1", RECORD_SCHEMA);

        String output = captureStdout(() ->
                assertEquals(2, ValidateCommand.run(List.of(schema), null, OutputFormat.TEXT)));

        assertTrue(output.contains("no data files"), output);
    }

    @Test
    void jsonOutputIsWellShaped(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "schema.tn1", RECORD_SCHEMA);
        Path data = writeFile(dir, "data.tson", selfDescribing("{ a: 99999999999999  b: 2 }"));

        String output = captureStdout(() ->
                assertEquals(1, ValidateCommand.run(List.of(schema, data), null, OutputFormat.JSON)));

        assertTrue(output.contains("\"valid\":false"), output);
        assertTrue(output.contains("\"code\":\"ATOM_CONSTRAINT_VIOLATION\""), output);
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
}
