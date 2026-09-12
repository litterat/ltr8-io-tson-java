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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code tson validate} over a JSON document: [TSON-JSON] §3.4's out-of-band binding on a command line.
 *
 * <p>A {@code .tn} document names its own schema and root type and needs nothing; a JSON document can name
 * neither, so {@code --schema} and {@code --type} supply both. The report, the exit codes and the policy
 * field are the run's rather than an encoding's -- one CLI over both, which is the point of wiring it here
 * rather than shipping a second tool.
 */
class JsonValidateTest {

    private static final String ID = "https://example.test/cli-json.tn";

    private static final String SCHEMA = """
            !!id:"https://example.test/cli-json.tn"
            !!meta:"https://tson.io/2026/36/m/meta.tn"
            !!import:"https://tson.io/2026/36/m/core.tn"
            {
              person => { name: text  tries: int32 ~ 0 }
            }
            """;

    @Test
    void aConformingJsonDocumentIsValid(@TempDir Path dir) throws IOException {
        Path schema = write(dir, "person.tn", SCHEMA);
        Path json = write(dir, "good.json", """
                {"name": "Ada"}""");
        String out = captureStdout(() -> assertEquals(0, TsonCli.run(args(schema, json))));
        assertEquals("OK", out.strip());
    }

    @Test
    void aNonConformingJsonDocumentIsExitOneWithItsProblemsLocated(@TempDir Path dir) throws IOException {
        Path schema = write(dir, "person.tn", SCHEMA);
        Path json = write(dir, "bad.json", """
                {"tries": "x", "shoe_size": 9}""");
        String out = captureStdout(() -> assertEquals(1, TsonCli.run(args(schema, json))));
        assertTrue(out.contains("FIELD_REQUIRED"), out);
        assertTrue(out.contains("/name"), out);
        assertTrue(out.contains("UNRECOGNIZED_FIELD"), out);
        assertTrue(out.contains("/shoe_size"), out);
    }

    /** One run, both encodings, one envelope -- and the verdict is the AND across the files. */
    @Test
    void aRunMayHoldBothEncodings(@TempDir Path dir) throws IOException {
        Path schema = write(dir, "person.tn", SCHEMA);
        Path json = write(dir, "good.json", """
                {"name": "Ada"}""");
        Path tson = write(dir, "good.tn", "!!schema:\"" + ID + "\"\n!person { name: \"Grace\" }\n");
        String out = captureStdout(() -> assertEquals(0, TsonCli.run(new String[] {
                "validate", "--schema", ID, "--type", "person",
                schema.toString(), json.toString(), tson.toString()})));
        // Two data files, so the text format names each rather than printing the bare one-file "OK".
        assertTrue(out.contains("good.json"), out);
        assertTrue(out.contains("good.tn"), out);
        assertFalse(out.contains("["), "no diagnostics: " + out);
    }

    /** §6.1.3's injection reaches the report's own document: decoded output is fully populated. */
    @Test
    void theJsonPathAppliesTheSchemaAndNotJustTheSyntax(@TempDir Path dir) throws IOException {
        Path schema = write(dir, "person.tn", SCHEMA);
        Path json = write(dir, "bad.json", """
                {"name": "Ada", "tries": 1.5}""");
        String out = captureStdout(() -> assertEquals(1, TsonCli.run(args(schema, json))));
        assertTrue(out.contains("ATOM_FORM_INVALID"), "1.5 is not an int32: " + out);
    }

    // ── Usage errors: the command line's, not a document's ──────────────

    @Test
    void aJsonInputWithNoBindingIsAUsageError(@TempDir Path dir) throws IOException {
        Path schema = write(dir, "person.tn", SCHEMA);
        Path json = write(dir, "good.json", "{}");
        String err = captureStderr(() -> assertEquals(2, TsonCli.run(new String[] {
                "validate", schema.toString(), json.toString()})));
        assertTrue(err.contains("--schema"), err);
        assertTrue(err.contains("--type"), err);
    }

    @Test
    void halfABindingIsAUsageError(@TempDir Path dir) throws IOException {
        Path schema = write(dir, "person.tn", SCHEMA);
        Path json = write(dir, "good.json", "{}");
        String err = captureStderr(() -> assertEquals(2, TsonCli.run(new String[] {
                "validate", "--schema", ID, schema.toString(), json.toString()})));
        assertTrue(err.contains("one binding"), err);
    }

    /** A flag that binds nothing is refused rather than ignored -- this CLI's habit, not a new rule. */
    @Test
    void aBindingWithNoJsonInputIsAUsageError(@TempDir Path dir) throws IOException {
        Path schema = write(dir, "person.tn", SCHEMA);
        Path tson = write(dir, "good.tn", "!!schema:\"" + ID + "\"\n!person { name: \"Ada\" }\n");
        String err = captureStderr(() -> assertEquals(2, TsonCli.run(new String[] {
                "validate", "--schema", ID, "--type", "person", schema.toString(), tson.toString()})));
        assertTrue(err.contains("bind JSON inputs"), err);
    }

    /**
     * A mistyped {@code --type} is the command line's mistake, so it is exit 2 and one message -- not exit 1
     * and an identical-looking verdict per file, which would say the documents were wrong.
     */
    @Test
    void anUndeclaredRootTypeIsAUsageErrorNamingWhatTheSchemaDeclares(@TempDir Path dir) throws IOException {
        Path schema = write(dir, "person.tn", SCHEMA);
        Path json = write(dir, "good.json", "{}");
        String out = captureStdout(() -> assertEquals(2, TsonCli.run(new String[] {
                "validate", "--schema", ID, "--type", "persno", schema.toString(), json.toString()})));
        assertTrue(out.contains("UNKNOWN_TYPE"), out);
        assertTrue(out.contains("person"), "the schema's own declarations lead the list: " + out);
        assertFalse(out.contains("(void |"), "core.tn's imports do not bury them: " + out);
    }

    /** A schema identity no file declares is the binding's problem, before any document is read. */
    @Test
    void aSchemaNoFileDeclaresIsAUsageError(@TempDir Path dir) throws IOException {
        Path schema = write(dir, "person.tn", SCHEMA);
        Path json = write(dir, "good.json", "{}");
        String out = captureStdout(() -> assertEquals(2, TsonCli.run(new String[] {
                "validate", "--schema", "https://example.test/absent.tn", "--type", "person",
                schema.toString(), json.toString()})));
        assertTrue(out.contains("SCHEMA_NOT_FOUND"), out);
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static String[] args(Path schema, Path json) {
        return new String[] {"validate", "--schema", ID, "--type", "person", schema.toString(), json.toString()};
    }

    private static Path write(Path dir, String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    private static String captureStdout(Runnable body) {
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

    private static String captureStderr(Runnable body) {
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
