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
            !!import:"https://tson.io/2026/40/m/meta.tn"
            !!import:"https://tson.io/2026/40/m/core.tn"
            !schema {
              person => { name: text  tries?: int32 ~ 0 }
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
        Path tson = write(dir, "good.tn", "!!import:\"" + ID + "\"\n!person { name: \"Grace\" }\n");
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

    /**
     * A schema declaring externs ([TSON-JSON] §8.6): the boundary's {@code $type} is the binding schema's name and
     * the one inside the foreign schema's, and a problem inside names the scope it was read in.
     */
    @Test
    void aForeignValueThroughAnExternValidates(@TempDir Path dir) throws IOException {
        Path one = write(dir, "order-1.tn", """
                !!id:"https://example.test/cli-order-1.tn"
                !!import:"https://tson.io/2026/40/m/core.tn"
                !schema { order => { id: text  payment: card }  card => { number: text } }
                """);
        Path two = write(dir, "order-2.tn", """
                !!id:"https://example.test/cli-order-2.tn"
                !!import:"https://tson.io/2026/40/m/core.tn"
                !schema { order => { sku: text  payment: card }  card => { number: text } }
                """);
        Path shipping = write(dir, "shipping.tn", """
                !!id:"https://example.test/cli-shipping.tn"
                !!import:"https://tson.io/2026/40/m/core.tn"
                !schema {
                  order_v1 => !meta.extern { schema: "https://example.test/cli-order-1.tn"  type: order }
                  order_v2 => !meta.extern { schema: "https://example.test/cli-order-2.tn"  type: order }
                  envelope => { id: text  body: (order_v1 | order_v2) }
                }
                """);
        Path good = write(dir, "good.json", """
                {"id": "e", "body": {"$type": "order_v2", "sku": "A", "payment": {"$type": "card", "number": "1"}}}""");
        Path bad = write(dir, "bad.json", """
                {"id": "e", "body": {"$type": "order_v2", "sku": "A", "payment": {"number": 1}}}""");
        String[] binding = {"validate", "--schema", shipping.toString(), "--type", "envelope", one.toString(),
                two.toString()};
        String ok = captureStdout(() -> assertEquals(0, TsonCli.run(append(binding, good))));
        assertEquals("OK", ok.strip());
        String out = captureStdout(() -> assertEquals(1, TsonCli.run(append(binding, bad))));
        assertTrue(out.contains("/body/payment/number"), out);
        assertTrue(out.contains("through the extern 'order_v2' of example.test/cli-shipping.tn"), out);
    }

    private static String[] append(String[] head, Path last) {
        String[] all = java.util.Arrays.copyOf(head, head.length + 1);
        all[head.length] = last.toString();
        return all;
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
        Path tson = write(dir, "good.tn", "!!import:\"" + ID + "\"\n!person { name: \"Ada\" }\n");
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
        assertTrue(out.contains("SCHEMA_NOT_PERMITTED"), out);
        assertTrue(out.contains(ID), "the identities the files do declare are listed: " + out);
    }

    /**
     * A schema file binds by the {@code !!id} it declares, wherever it sits -- the path is how the file reaches
     * the run, never what matches -- so it need not be listed again, nor its identity restated.
     */
    @Test
    void aSchemaFileBindsByTheIdentityItDeclares(@TempDir Path dir) throws IOException {
        Path schema = write(dir, "local-draft.tn", SCHEMA);
        Path json = write(dir, "good.json", """
                {"name": "Ada"}""");
        String out = captureStdout(() -> assertEquals(0, TsonCli.run(new String[] {
                "validate", "--schema", schema.toString(), "--type", "person", json.toString()})));
        assertEquals("OK", out.strip());
    }

    /**
     * A bound schema that does not load for a gap in this library is no verdict on the schema: the run reports the
     * gap as one and exits 70, as a gap anywhere else in a run does.
     */
    @Test
    void aBoundSchemaWithAGapIsExitSeventy(@TempDir Path dir) throws IOException {
        Path schema = write(dir, "gap.tn", """
                !!id:"https://example.test/cli-gap.tn"
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !!import:"https://tson.io/2026/40/m/core.tn"
                !schema {
                  c => meta.product & { extra: text }
                  x => !c { extra: "hello" }
                  person => { name: text }
                }
                """);
        Path json = write(dir, "good.json", """
                {"name": "Ada"}""");
        String out = captureStdout(() -> assertEquals(70, TsonCli.run(new String[] {
                "validate", "--schema", schema.toString(), "--type", "person", json.toString()})));
        assertTrue(out.contains("NOT_IMPLEMENTED"), out);
        assertFalse(out.contains("SCHEMA_ERROR"), out);
    }

    /** Named by {@code --schema} and listed as well, it is one schema and loads once. */
    @Test
    void aSchemaFileMayAlsoBeListed(@TempDir Path dir) throws IOException {
        Path schema = write(dir, "person.tn", SCHEMA);
        Path json = write(dir, "bad.json", "{}");
        String out = captureStdout(() -> assertEquals(1, TsonCli.run(new String[] {
                "validate", "--output", "json", "--schema", schema.toString(), "--type", "person",
                schema.toString(), json.toString()})));
        assertTrue(out.contains("FIELD_REQUIRED"), out);
    }

    @Test
    void aFileThatIsNotASchemaDocumentIsAUsageError(@TempDir Path dir) throws IOException {
        Path json = write(dir, "good.json", "{}");
        String out = captureStdout(() -> assertEquals(2, TsonCli.run(new String[] {
                "validate", "--schema", json.toString(), "--type", "person", json.toString()})));
        assertTrue(out.contains("not a schema document"), out);
    }

    /**
     * A value naming no file and no identity -- a mistyped path, most often -- is the binding's problem, not a
     * fault in this command, and the identities the schema files do declare are named beside it.
     */
    @Test
    void aValueThatIsNeitherAFileNorAnIdentityIsAUsageError(@TempDir Path dir) throws IOException {
        Path schema = write(dir, "person.tn", SCHEMA);
        Path json = write(dir, "good.json", "{}");
        String out = captureStdout(() -> assertEquals(2, TsonCli.run(new String[] {
                "validate", "--schema", "persno.tn", "--type", "person", schema.toString(), json.toString()})));
        assertTrue(out.contains("SCHEMA_NOT_PERMITTED"), out);
        assertTrue(out.contains("neither a file nor a schema identity"), out);
        assertTrue(out.contains(ID), "the identities the files do declare are listed: " + out);
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
