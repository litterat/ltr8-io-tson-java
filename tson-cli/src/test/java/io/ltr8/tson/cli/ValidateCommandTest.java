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

class ValidateCommandTest {

    private static final String SCHEMA = """
            !!id:"https://example.test/cli-validate.tn1"
            !!meta:"https://tson.io/2026/32/m/meta.tn"
            !!import:"https://tson.io/2026/32/m/core.tn"
            {
              my_int => int32
            }
            """;

    @Test
    void validDataExitsZeroAndReportsOk(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "schema.tn1", SCHEMA);
        Path data = writeFile(dir, "valid.tson", "42");

        String output = captureStdout(() ->
                assertEquals(0, ValidateCommand.run(schema, "my_int", List.of(data), OutputFormat.TEXT)));

        assertEquals("OK", output.strip());
    }

    @Test
    void invalidDataExitsOneAndReportsAValidationError(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "schema.tn1", SCHEMA);
        Path data = writeFile(dir, "invalid.tson", "\"oops\"");

        String output = captureStdout(() ->
                assertEquals(1, ValidateCommand.run(schema, "my_int", List.of(data), OutputFormat.TEXT)));

        assertTrue(output.contains("[VALIDATION_ERROR]"), output);
    }

    @Test
    void aSchemaThatFailsToCompileExitsTwo(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "broken.tn1", """
                !!id:"https://example.test/cli-broken.tn1"
                !!meta:"https://tson.io/2026/32/m/meta.tn"
                {
                  my_int => this_type_does_not_exist
                }
                """);
        Path data = writeFile(dir, "data.tson", "42");

        String output = captureStdout(() ->
                assertEquals(2, ValidateCommand.run(schema, "my_int", List.of(data), OutputFormat.TEXT)));

        assertTrue(output.contains("[SCHEMA_ERROR]"), output);
    }

    @Test
    void anUnknownTypeNameExitsTwo(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "schema.tn1", SCHEMA);
        Path data = writeFile(dir, "valid.tson", "42");

        String output = captureStdout(() ->
                assertEquals(2, ValidateCommand.run(schema, "does_not_exist", List.of(data), OutputFormat.TEXT)));

        assertTrue(output.contains("[UNKNOWN_TYPE]"), output);
    }

    @Test
    void jsonOutputIsWellShaped(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "schema.tn1", SCHEMA);
        Path data = writeFile(dir, "invalid.tson", "\"oops\"");

        String output = captureStdout(() ->
                assertEquals(1, ValidateCommand.run(schema, "my_int", List.of(data), OutputFormat.JSON)));

        assertTrue(output.contains("\"valid\":false"), output);
        assertTrue(output.contains("\"code\":\"VALIDATION_ERROR\""), output);
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
