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
                !!meta:"https://tson.io/2026/32/m/meta.tn1"
                !!import:"https://tson.io/2026/32/m/core.tn1"
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
                !!meta:"https://tson.io/2026/32/m/meta.tn1"
                {
                  my_int => this_type_does_not_exist
                }
                """);

        String output = captureStdout(() -> assertEquals(1, CompileCommand.run(schema, OutputFormat.TEXT)));

        assertTrue(output.contains("[COMPILE_ERROR]"), output);
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
