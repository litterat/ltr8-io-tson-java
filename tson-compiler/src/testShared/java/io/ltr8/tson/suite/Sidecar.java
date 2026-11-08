package io.ltr8.tson.suite;

import io.ltr8.tson.compiler.TsonDataParser;
import io.ltr8.tson.compiler.ast.VoidValue;
import io.ltr8.tson.compiler.ast.ArrayValue;
import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.Document;
import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.compiler.ast.TokenValue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Reading a conformance vector's sidecar, and preparing its subject -- the half of {@code RUNNER.md} that
 * is the same whatever layer a runner is testing.
 *
 * <p><b>Shared by the Class 1 and Class 2 runners</b>, which live in different modules. The sidecar
 * contract is one contract: the outcome group, the field accessors that know {@code _} from a missing
 * field, and how a subject is read. Two copies of it is how the first two runners came to disagree about what a
 * subject even is, which is the reason {@code RUNNER.md} was written.
 *
 * <p>The sidecar is parsed with the real {@link TsonDataParser} -- rule 2, deliberate dogfooding: a broken
 * parser fails loudly rather than quietly agreeing with itself.
 */
public final class Sidecar {

    /** The outcome group's members, across every layer's sidecar schema. */
    private static final List<String> OUTCOMES = List.of("valid", "error", "schema-document", "refused");

    private Sidecar() {
    }

    // ── The sidecar document ─────────────────────────────────────────────

    /** A sidecar's root record. It is itself TSON, parsed by the implementation under test (rule 2). */
    public static RecordValue parse(Path sidecar) throws IOException {
        String text = readRaw(sidecar);
        Document document;
        try {
            document = new TsonDataParser(text).parseDocument();
        } catch (RuntimeException e) {
            throw new AssertionError("sidecar " + sidecar + " is not valid TSON: " + e.getMessage(), e);
        }
        return assertInstanceOf(RecordValue.class, document.root().coreValue(), "sidecar root must be a record");
    }

    /**
     * The name of the outcome group's present member -- {@code valid}, {@code error}, the parser layer's
     * {@code schema-document}, or the reader layer's {@code refused}. §5.11 makes the group REQUIRED, so
     * exactly one is present and the member label <em>is</em> the outcome: there is no separate {@code
     * outcome} field to disagree with the payload beside it.
     */
    public static String outcomeOf(RecordValue sidecar) {
        for (RecordValue.Field field : sidecar.fields()) {
            if (OUTCOMES.contains(field.name())) {
                return field.name();
            }
        }
        throw new AssertionError("sidecar states no outcome; expected one of " + OUTCOMES);
    }

    /** The present outcome member's own payload record. */
    public static RecordValue outcomePayload(RecordValue sidecar) {
        return (RecordValue) fieldCore(sidecar, outcomeOf(sidecar));
    }

    // ── The subject ──────────────────────────────────────────────────────

    /**
     * {@code subject}'s own bytes, as they are on disk. Every subject is a complete document, its header
     * importing what it needs, so nothing is spliced into it ({@code RUNNER.md}).
     *
     * <p><b>Bytes, never a decoded string</b> (rule 1): reading a subject into a {@code String} re-encodes
     * it, which is harmless for text and destroys exactly the vectors that exist to be destroyed.
     */
    public static byte[] subjectBytes(Path subject) throws IOException {
        return Files.readAllBytes(subject);
    }

    /** {@code subject} as text, for a caller that hands source to a parser rather than a stream. */
    public static String subjectSource(Path subject) throws IOException {
        return readRaw(subject);
    }

    // ── Field accessors ──────────────────────────────────────────────────

    /** The one field of a single-member group record, named for the message when there isn't exactly one. */
    public static RecordValue.Field soleField(RecordValue group, String what) {
        assertEquals(1, group.fields().size(), what + " must state exactly one kind");
        return group.fields().get(0);
    }

    public static DataValue fieldValue(RecordValue record, String name) {
        for (RecordValue.Field field : record.fields()) {
            if (field.name().equals(name)) {
                return field.value();
            }
        }
        throw new AssertionError("sidecar record is missing field '" + name + "'");
    }

    public static CoreValue fieldCore(RecordValue record, String name) {
        return fieldValue(record, name).coreValue();
    }

    public static String fieldText(RecordValue record, String name) {
        return assertInstanceOf(TokenValue.class, fieldCore(record, name), "field '" + name + "'").text();
    }

    /** Like {@link #fieldText}, but the field may be the void sentinel {@code _}, returning null then. */
    public static String fieldTextOrAbsent(RecordValue record, String name) {
        DataValue value = fieldValue(record, name);
        return (value.coreValue() instanceof VoidValue) ? null : fieldText(record, name);
    }

    /**
     * Unlike {@link #fieldTextOrAbsent}, for a field that may be missing from the record entirely rather
     * than present with the value {@code _} -- what an optional sidecar field like {@code
     * path} actually looks like when a vector does not use it.
     */
    public static boolean hasField(RecordValue record, String name) {
        return record.fields().stream().anyMatch(field -> field.name().equals(name));
    }

    /** Each element of an array-of-tokens field, as plain text. */
    public static List<String> fieldTextArray(RecordValue record, String name) {
        List<String> result = new ArrayList<>();
        for (DataValue element : ((ArrayValue) fieldCore(record, name)).elements()) {
            result.add(assertInstanceOf(TokenValue.class, element.coreValue(),
                    "field '" + name + "' element").text());
        }
        return result;
    }

    /** Each element of an array-of-records field, as its own record. */
    public static List<RecordValue> fieldRecordArray(RecordValue record, String name) {
        List<RecordValue> result = new ArrayList<>();
        for (DataValue element : ((ArrayValue) fieldCore(record, name)).elements()) {
            result.add(assertInstanceOf(RecordValue.class, element.coreValue(),
                    "field '" + name + "' element"));
        }
        return result;
    }

    public static String readRaw(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
