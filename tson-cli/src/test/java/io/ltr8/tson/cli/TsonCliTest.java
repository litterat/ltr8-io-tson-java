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
    void validateWithoutTypeExitsTwo(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "schema.tn1", """
                !!id:"https://example.test/cli-arg-test.tn1"
                !!meta:"https://tson.io/2026/32/m/meta.tn"
                !!import:"https://tson.io/2026/32/m/core.tn"
                { my_int => int32 }
                """);
        Path data = writeFile(dir, "data.tson", "42");

        String err = captureStderr(() ->
                assertEquals(2, TsonCli.run(new String[] {"validate", schema.toString(), data.toString()})));

        assertTrue(err.contains("--type"), err);
    }

    @Test
    void validateEndToEndThroughMainDispatchExitsZeroForValidData(@TempDir Path dir) throws IOException {
        Path schema = writeFile(dir, "schema.tn1", """
                !!id:"https://example.test/cli-arg-test-2.tn1"
                !!meta:"https://tson.io/2026/32/m/meta.tn"
                !!import:"https://tson.io/2026/32/m/core.tn"
                { my_int => int32 }
                """);
        Path data = writeFile(dir, "data.tson", "42");

        int exitCode = TsonCli.run(new String[] {
                "validate", "--type", "my_int", "--output", "json", schema.toString(), data.toString()});

        assertEquals(0, exitCode);
    }

    @Test
    void initScaffoldsAnExampleThatActuallyValidates(@TempDir Path dir) {
        assertEquals(0, TsonCli.run(new String[] {"init-example", dir.toString()}));

        Path schema = dir.resolve("person.tn");
        Path data = dir.resolve("person-data.tn");
        assertTrue(Files.exists(schema), "person.tn written");
        assertTrue(Files.exists(data), "person-data.tn written");

        // The whole point: the scaffolded pair the README's getting-started walks through must
        // validate cleanly, so onboarding can never ship a broken example.
        assertEquals(0, TsonCli.run(new String[] {
                "validate", "--type", "person", schema.toString(), data.toString()}));
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
