package io.ltr8.tson.cli;

import io.ltr8.tson.compiler.TsonContentHash;
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

class HashCommandTest {

    private static final String SCHEMA = """
            !!id:"https://example.test/thing-1.tn"
            !!meta:"https://tson.io/2026/33/m/meta.tn"
            { thing => int32 }
            """;

    @Test
    void stampsTheContentHashOntoTheId(@TempDir Path dir) throws IOException {
        Path file = writeFile(dir, "thing.tn", SCHEMA);
        String expected = TsonContentHash.sha256(SCHEMA.getBytes(StandardCharsets.UTF_8));

        assertEquals(0, HashCommand.run(file));

        String firstLine = Files.readString(file).lines().findFirst().orElseThrow();
        assertEquals("!!id:\"https://example.test/thing-1.tn?sha256=" + expected + "\"", firstLine);
    }

    @Test
    void theHashedBodyIsUnchangedSoTheHashVerifies(@TempDir Path dir) throws IOException {
        Path file = writeFile(dir, "thing.tn", SCHEMA);
        assertEquals(0, HashCommand.run(file));

        // The stamped hash must equal a fresh hash of the now-on-disk file (id line excluded either way).
        byte[] onDisk = Files.readAllBytes(file);
        String recomputed = TsonContentHash.sha256(onDisk);
        String stamped = Files.readString(file).lines().findFirst().orElseThrow()
                .replaceAll(".*sha256=([0-9a-f]+).*", "$1");
        assertEquals(recomputed, stamped);
    }

    @Test
    void isIdempotentAndReplacesAPriorPin(@TempDir Path dir) throws IOException {
        Path file = writeFile(dir, "thing.tn", SCHEMA);
        assertEquals(0, HashCommand.run(file));
        String afterOnce = Files.readString(file);

        assertEquals(0, HashCommand.run(file));
        String afterTwice = Files.readString(file);

        assertEquals(afterOnce, afterTwice, "re-hashing must be a no-op, not double-append");
        long pins = afterTwice.lines().findFirst().orElseThrow().chars().filter(c -> c == '?').count();
        assertEquals(1, pins, "exactly one query on the id");
    }

    @Test
    void aFileWithoutAnIdLineIsRejected(@TempDir Path dir) throws IOException {
        Path file = writeFile(dir, "data.tn", "{ a: 1 }\n");   // first line is not !!id

        String err = captureStderr(() -> assertEquals(1, HashCommand.run(file)));

        assertTrue(err.contains("!!id"), err);
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
}
