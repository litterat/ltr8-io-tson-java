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

class StripCommandTest {

    private static final String SCHEMA = """
            !!id:"https://example.test/thing-1.tn"
            !!meta:"https://tson.io/2026/37/m/meta.tn"
            !!import:"https://tson.io/2026/37/m/core.tn"
            {
              @doc:"A thing."
              thing => int32
            }
            """;

    @Test
    void printsTheReadingFormAndLeavesTheFileAlone(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("thing.tn"), SCHEMA);

        String out = capture(true, () -> assertEquals(0, TsonCli.run(new String[] {"strip", file.toString()})));

        assertEquals("!!meta:\"37/meta\"\n!!import:\"37/core\"\n{\nthing => int32\n}\n", out);
        assertEquals(SCHEMA, Files.readString(file));
    }

    @Test
    void aMalformedSchemaIsRejectedWhereItBreaks(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("broken.tn"), """
                !!meta:"https://tson.io/2026/37/m/meta.tn"
                { thing => }
                """);

        String err = capture(false, () -> assertEquals(1, StripCommand.run(file)));

        assertTrue(err.startsWith(file + ":2:"), err);
    }

    @Test
    void anUnreadableFileIsAUsageError(@TempDir Path dir) throws IOException {
        String err = capture(false, () -> assertEquals(2, StripCommand.run(dir.resolve("missing.tn"))));

        assertTrue(err.startsWith("cannot read"), err);
    }

    private interface ThrowingRunnable {
        void run() throws IOException;
    }

    private static String capture(boolean stdout, ThrowingRunnable body) throws IOException {
        PrintStream original = stdout ? System.out : System.err;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream replacement = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        if (stdout) {
            System.setOut(replacement);
        } else {
            System.setErr(replacement);
        }
        try {
            body.run();
        } finally {
            if (stdout) {
                System.setOut(original);
            } else {
                System.setErr(original);
            }
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }
}
