package io.ltr8.tson.parser;

import io.ltr8.tson.parser.ast.AbsentValue;
import io.ltr8.tson.parser.ast.ArrayValue;
import io.ltr8.tson.parser.ast.CoreValue;
import io.ltr8.tson.parser.ast.DataValue;
import io.ltr8.tson.parser.ast.Document;
import io.ltr8.tson.parser.ast.EmptyBrace;
import io.ltr8.tson.parser.ast.MapValue;
import io.ltr8.tson.parser.ast.RecordValue;
import io.ltr8.tson.parser.ast.ScopedValue;
import io.ltr8.tson.parser.ast.TokenValue;
import io.ltr8.tson.parser.lexer.LexException;
import io.ltr8.tson.parser.lexer.Lexer;
import io.ltr8.tson.parser.lexer.Token;
import io.ltr8.tson.parser.lexer.TokenType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs every vector in the sibling {@code ltr8-io-tson-test-suite} repo (see its own README for
 * the vector/sidecar format) against this implementation's real {@link Lexer} and {@link Parser}.
 *
 * <p>This is deliberately separate from {@link io.ltr8.tson.parser.lexer.LexerTest} and
 * {@link ParserTest}: those are fine-grained unit tests of individual grammar rules with
 * assertion messages that point at exactly what broke. This is a conformance/integration test
 * against an external, language-agnostic, spec-derived fixture set shared with (potentially)
 * other implementations -- it exists to catch drift between this implementation and the spec,
 * not to pinpoint which internal rule regressed.
 *
 * <p>The sibling repo is assumed to be checked out next to this one
 * ({@code ../../ltr8-io-tson-test-suite} relative to this module's directory, which is Gradle's
 * and most IDEs' default test working directory). If it isn't present -- as in CI, which
 * deliberately doesn't check it out -- every {@code @TestFactory} here is skipped via
 * {@link Assumptions}, not failed.
 */
class ConformanceSuiteTest {

    private static final Path SUITE_TESTS_ROOT =
            Paths.get("").toAbsolutePath().resolve("../../ltr8-io-tson-test-suite/tests").normalize();

    @TestFactory
    Stream<DynamicTest> lexerVectors() {
        return vectorsIn("lexer", ConformanceSuiteTest::checkLexerVector);
    }

    @TestFactory
    Stream<DynamicTest> parserVectors() {
        return vectorsIn("parser", ConformanceSuiteTest::checkParserVector);
    }

    private interface VectorCheck {
        void check(String bucket, Path tn1, RecordValue sidecarBody) throws IOException;
    }

    private Stream<DynamicTest> vectorsIn(String layer, VectorCheck check) {
        Path layerRoot = SUITE_TESTS_ROOT.resolve(layer);
        Assumptions.assumeTrue(Files.isDirectory(layerRoot),
                "ltr8-io-tson-test-suite not found at " + SUITE_TESTS_ROOT
                        + " (expected a sibling checkout) -- skipping conformance vectors");

        try (Stream<Path> buckets = Files.list(layerRoot)) {
            return buckets
                    .filter(Files::isDirectory)
                    .sorted()
                    .flatMap(bucket -> vectorsInBucket(layer, bucket, check))
                    .toList()
                    .stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Stream<DynamicTest> vectorsInBucket(String layer, Path bucketDir, VectorCheck check) {
        String bucket = bucketDir.getFileName().toString();
        try (Stream<Path> files = Files.list(bucketDir)) {
            return files
                    .filter(p -> p.toString().endsWith(".tn1"))
                    .sorted()
                    .map(tn1 -> {
                        String slug = tn1.getFileName().toString().replace(".tn1", "");
                        Path tson = bucketDir.resolve(slug + ".tson");
                        String name = layer + "/" + bucket + "/" + slug;
                        return DynamicTest.dynamicTest(name, () -> {
                            RecordValue sidecarBody = parseSidecarBody(tson);
                            check.check(bucket, tn1, sidecarBody);
                        });
                    })
                    .toList()
                    .stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The sidecar is itself TSON: a document whose root value is a record. Parsed with the real Parser. */
    private static RecordValue parseSidecarBody(Path tsonPath) throws IOException {
        String text = readRaw(tsonPath);
        Document doc;
        try {
            doc = new Parser(text).parseDocument();
        } catch (RuntimeException e) {
            throw new AssertionError("sidecar " + tsonPath + " is not valid TSON: " + e.getMessage(), e);
        }
        return assertInstanceOf(RecordValue.class, doc.root().coreValue(),
                "sidecar root must be a record");
    }

    // ── Lexer-layer vectors ──────────────────────────────────────────────

    private static void checkLexerVector(String bucket, Path tn1, RecordValue sidecar) throws IOException {
        String outcome = fieldText(sidecar, "outcome");
        String raw = readRaw(tn1);
        switch (outcome) {
            case "valid" -> {
                List<Token> actual = new Lexer(raw).tokenize();
                actual.removeIf(t -> t.type() == TokenType.EOF);
                ArrayValue expectedTokens = (ArrayValue) fieldCore(sidecar, "tokens");
                assertEquals(expectedTokens.elements().size(), actual.size(), "token count");
                for (int i = 0; i < actual.size(); i++) {
                    RecordValue expTok = (RecordValue) expectedTokens.elements().get(i).value().coreValue();
                    String expKind = fieldText(expTok, "kind");
                    String expText = fieldText(expTok, "text");
                    assertEquals(expKind, kindName(actual.get(i).type()), "token[" + i + "].kind");
                    assertEquals(expText, actual.get(i).text(), "token[" + i + "].text");
                }
            }
            case "error" -> assertThrows(LexException.class, () -> new Lexer(raw).tokenize());
            default -> fail("unknown lexer-layer outcome: " + outcome);
        }
    }

    private static String kindName(TokenType t) {
        return switch (t) {
            case SINGLE_LINE_STRING -> "single-line-token";
            case MULTI_LINE_STRING -> "multi-line-token";
            case UNQUOTED -> "unquoted-token";
            case ABSENT -> "absent-token";
            case LBRACE, RBRACE, LBRACKET, RBRACKET, COLON, COMMA -> "structural-delimiter";
            case MAP_ARROW -> "map-arrow-token";
            case DIRECTIVE -> "directive-token";
            case RANGE -> "range-token";
            default -> "special-token";
        };
    }

    // ── Parser-layer vectors ─────────────────────────────────────────────

    private static void checkParserVector(String bucket, Path tn1, RecordValue sidecar) throws IOException {
        String outcome = fieldText(sidecar, "outcome");
        String raw = readRaw(tn1);
        switch (outcome) {
            case "valid" -> {
                Document actual = new Parser(raw).parseDocument();
                RecordValue expectedDoc = (RecordValue) fieldCore(sidecar, "document");
                assertDocumentMatches(expectedDoc, actual);
            }
            case "error" -> assertThrows(ParseException.class, () -> new Parser(raw).parseDocument());
            case "schema-document" -> assertThrows(SchemaDocumentException.class,
                    () -> new Parser(raw).parseDocument());
            default -> fail("unknown parser-layer outcome: " + outcome);
        }
    }

    private static void assertDocumentMatches(RecordValue expected, Document actual) {
        assertEquals(fieldTextOrAbsent(expected, "id"), actual.id().orElse(null), "document.id");
        assertEquals(fieldTextOrAbsent(expected, "schema"), actual.schema().orElse(null), "document.schema");
        RecordValue expectedRoot = (RecordValue) fieldValue(expected, "root").coreValue();
        assertDataValueMatches(expectedRoot, actual.root());
    }

    private static void assertDataValueMatches(RecordValue expected, DataValue actual) {
        List<DataValue> expectedAnnotations = new ArrayList<>();
        for (ScopedValue sv : ((ArrayValue) fieldCore(expected, "annotations")).elements()) {
            expectedAnnotations.add(sv.value());
        }
        assertEquals(expectedAnnotations.size(), actual.annotations().size(), "annotation count");
        for (int i = 0; i < expectedAnnotations.size(); i++) {
            RecordValue expAnn = (RecordValue) expectedAnnotations.get(i).coreValue();
            var actAnn = actual.annotations().get(i);
            assertEquals(fieldText(expAnn, "name"), actAnn.name(), "annotation[" + i + "].name");

            DataValue expAnnValue = fieldValue(expAnn, "value");
            boolean expectsValue = !(expAnnValue.coreValue() instanceof AbsentValue);
            assertEquals(expectsValue, actAnn.value().isPresent(), "annotation[" + i + "].value presence");
            if (expectsValue) {
                assertDataValueMatches((RecordValue) expAnnValue.coreValue(), actAnn.value().orElseThrow());
            }
        }

        String expTypeRef = fieldTextOrAbsent(expected, "type-ref");
        assertEquals(expTypeRef, actual.typeRef().orElse(null), "type-ref");

        assertCoreValueMatches((RecordValue) fieldValue(expected, "core").coreValue(), actual.coreValue());
    }

    private static void assertCoreValueMatches(RecordValue expected, CoreValue actual) {
        String kind = fieldText(expected, "kind");
        switch (kind) {
            case "token" -> {
                TokenValue tv = assertInstanceOf(TokenValue.class, actual, "core-value kind 'token'");
                String expForm = fieldText(expected, "form");
                String actForm = switch (tv.form()) {
                    case UNQUOTED -> "unquoted";
                    case SINGLE_LINE_QUOTED -> "single-line";
                    case MULTI_LINE_QUOTED -> "multi-line";
                };
                assertEquals(expForm, actForm, "token form");
                assertEquals(fieldText(expected, "text"), tv.text(), "token text");
            }
            case "absent" -> assertInstanceOf(AbsentValue.class, actual, "core-value kind 'absent'");
            case "empty-brace" -> assertInstanceOf(EmptyBrace.class, actual, "core-value kind 'empty-brace'");
            case "record" -> {
                RecordValue rv = assertInstanceOf(RecordValue.class, actual, "core-value kind 'record'");
                ArrayValue expFields = (ArrayValue) fieldCore(expected, "fields");
                assertEquals(expFields.elements().size(), rv.fields().size(), "record field count");
                for (int i = 0; i < rv.fields().size(); i++) {
                    RecordValue expField = (RecordValue) expFields.elements().get(i).value().coreValue();
                    assertEquals(fieldText(expField, "name"), rv.fields().get(i).name(),
                            "record field[" + i + "].name");
                    assertScopedValueMatches((RecordValue) fieldValue(expField, "value").coreValue(),
                            rv.fields().get(i).value());
                }
            }
            case "map" -> {
                MapValue mv = assertInstanceOf(MapValue.class, actual, "core-value kind 'map'");
                ArrayValue expEntries = (ArrayValue) fieldCore(expected, "entries");
                assertEquals(expEntries.elements().size(), mv.entries().size(), "map entry count");
                for (int i = 0; i < mv.entries().size(); i++) {
                    RecordValue expEntry = (RecordValue) expEntries.elements().get(i).value().coreValue();
                    assertDataValueMatches((RecordValue) fieldValue(expEntry, "key").coreValue(),
                            mv.entries().get(i).key());
                    assertScopedValueMatches((RecordValue) fieldValue(expEntry, "value").coreValue(),
                            mv.entries().get(i).value());
                }
            }
            case "array" -> {
                ArrayValue av = assertInstanceOf(ArrayValue.class, actual, "core-value kind 'array'");
                ArrayValue expElements = (ArrayValue) fieldCore(expected, "elements");
                assertEquals(expElements.elements().size(), av.elements().size(), "array element count");
                for (int i = 0; i < av.elements().size(); i++) {
                    RecordValue expScoped = (RecordValue) expElements.elements().get(i).value().coreValue();
                    assertScopedValueMatches(expScoped, av.elements().get(i));
                }
            }
            default -> fail("unknown expected core-value kind: " + kind);
        }
    }

    private static void assertScopedValueMatches(RecordValue expected, ScopedValue actual) {
        assertEquals(fieldTextOrAbsent(expected, "schema-ref"), actual.schemaRef().orElse(null), "schema-ref");
        assertDataValueMatches((RecordValue) fieldValue(expected, "value").coreValue(), actual.value());
    }

    // ── Sidecar field helpers ────────────────────────────────────────────

    private static DataValue fieldValue(RecordValue r, String name) {
        for (RecordValue.Field f : r.fields()) {
            if (f.name().equals(name)) {
                return f.value().value();
            }
        }
        throw new AssertionError("sidecar record is missing field '" + name + "'");
    }

    private static CoreValue fieldCore(RecordValue r, String name) {
        return fieldValue(r, name).coreValue();
    }

    private static String fieldText(RecordValue r, String name) {
        return assertInstanceOf(TokenValue.class, fieldCore(r, name), "field '" + name + "'").text();
    }

    /** Like {@link #fieldText}, but the field may be the absent sentinel {@code _}, returning null then. */
    private static String fieldTextOrAbsent(RecordValue r, String name) {
        DataValue v = fieldValue(r, name);
        return (v.coreValue() instanceof AbsentValue) ? null : fieldText(r, name);
    }

    private static String readRaw(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }
}
