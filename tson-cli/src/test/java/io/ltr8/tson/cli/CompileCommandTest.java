package io.ltr8.tson.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompileCommandTest {

    @Test
    void aCleanSchemaCompilesAndExitsZero(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "schema.tn1", """
                !!id:"https://example.test/cli-compile-ok.tn1"
                !!meta:"https://tson.io/2026/34/m/meta.tn"
                !!import:"https://tson.io/2026/34/m/core.tn"
                {
                  my_int => int32
                  my_record => { value: int32 }
                }
                """);

        String output = captureStdout(() -> assertEquals(0, CompileCommand.run(schema, OutputFormat.TEXT)));

        assertEquals("OK", output.strip());
    }

    @Test
    void aSchemaWithAnUnresolvableReferenceExitsOne(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "broken.tn1", """
                !!id:"https://example.test/cli-compile-broken.tn1"
                !!meta:"https://tson.io/2026/34/m/meta.tn"
                {
                  my_int => this_type_does_not_exist
                }
                """);

        String output = captureStdout(() -> assertEquals(1, CompileCommand.run(schema, OutputFormat.TEXT)));

        assertTrue(output.contains("[SCHEMA_ERROR]"), output);
    }

    /**
     * <b>69, not 1.</b> The CLI serves only the bundled standard library, so an {@code !!import} naming
     * anything else cannot be obtained -- and a schema whose imports never arrived was not checked, so
     * calling it invalid claims a verdict this run did not reach. The distinction is the whole of the
     * difference between "your schema is wrong" and "I could not get the other half of it".
     */
    @Test
    void aSchemaWhoseImportCannotBeObtainedExitsSixtyNine(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "needs-import.tn1", """
                !!id:"https://example.test/cli-compile-import.tn1"
                !!meta:"https://tson.io/2026/34/m/meta.tn"
                !!import:"https://example.test/nobody-serves-this.tn"
                {
                  my_int => int32
                }
                """);

        String output = captureStdout(() -> assertEquals(69, CompileCommand.run(schema, OutputFormat.TEXT)));

        assertTrue(output.contains("[SCHEMA_UNAVAILABLE]"), output);
        assertTrue(output.contains("nobody-serves-this"), output);
    }

    /**
     * The whole reason this issue was worth doing, end to end: a JSON-Schema-shaped refinement used to
     * print {@code OK} and then enforce nothing. Exit 1 (the author's schema is wrong), not exit 70 (a
     * fault in this library) -- {@code TsonCli} keeps those apart, and a body error wore the wrong
     * exception type until the classification split landed.
     */
    @Test
    void aRefinementUsingJsonSchemaFacetNamesExitsOneAndNamesTheRealVocabulary(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "json-shaped.tn1", """
                !!id:"https://example.test/cli-compile-json-shaped.tn1"
                !!meta:"https://tson.io/2026/34/m/meta.tn"
                !!import:"https://tson.io/2026/34/m/core.tn"
                {
                  quantity_t => !integer ^ { minimum: 1  maximum: 100 }
                }
                """);

        String output = captureStdout(() -> assertEquals(1, CompileCommand.run(schema, OutputFormat.TEXT)));

        assertTrue(output.contains("[SCHEMA_ERROR]"), output);
        assertTrue(output.contains("/quantity_t"), output);
        assertTrue(output.contains("unknown field 'minimum'"), output);
        assertTrue(output.contains("min"), output);
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
