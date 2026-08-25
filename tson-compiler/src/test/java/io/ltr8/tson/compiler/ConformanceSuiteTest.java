package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.ast.AbsentValue;
import io.ltr8.tson.compiler.ast.ArrayValue;
import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.Document;
import io.ltr8.tson.compiler.ast.EmptyBrace;
import io.ltr8.tson.compiler.ast.MapValue;
import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.compiler.ast.ScopedValue;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.lexer.LexException;
import io.ltr8.tson.compiler.lexer.Lexer;
import io.ltr8.tson.compiler.lexer.Token;
import io.ltr8.tson.compiler.lexer.TokenType;
import io.ltr8.tson.compiler.base.BaseTypeResolver;
import io.ltr8.tson.compiler.base.BaseValue;
import io.ltr8.tson.compiler.base.NumberForm;
import io.ltr8.tson.compiler.atom.AtomParseException;
import io.ltr8.tson.compiler.atom.AtomType;
import io.ltr8.tson.compiler.atom.AtomTypeException;
import io.ltr8.tson.compiler.atom.AtomValidationException;
import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.compiler.atom.BuiltinTypeVocabulary;
import io.ltr8.tson.compiler.atom.Complex;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.IsoDuration;
import io.ltr8.tson.schema.meta.Rational;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs every vector in the sibling {@code ltr8-io-tson-test-suite} repo (see its own README for
 * the vector/sidecar format) against this implementation's real {@link Lexer}, {@link TsonDataParser},
 * {@link BaseTypeResolver}, and {@link BuiltinTypeVocabulary}.
 *
 * <p>This is deliberately separate from {@link io.ltr8.tson.compiler.lexer.LexerTest} and
 * {@link TsonDataParserTest}: those are fine-grained unit tests of individual grammar rules with
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

    /**
     * Short, unversioned names a vector's own sidecar may use for {@code meta}/{@code import}
     * (see {@link #resolvedRaw}) instead of hardcoding the current spec revision's real, versioned
     * identity -- resolved directly off {@link TsonBundledSchemas}'s own constants, so a future
     * version bump only touches that one class, not every vector that references core.tn.
     */
    private static final Map<String, String> SCHEMA_SHORT_NAMES = Map.of(
            "meta-kernel.tn", TsonBundledSchemas.META_KERNEL_ID,
            "meta.tn", TsonBundledSchemas.META_ID,
            "core.tn", TsonBundledSchemas.CORE_ID
    );

    @TestFactory
    Stream<DynamicTest> lexerVectors() {
        return vectorsIn("lexer", ConformanceSuiteTest::checkLexerVector);
    }

    @TestFactory
    Stream<DynamicTest> parserVectors() {
        return vectorsIn("parser", ConformanceSuiteTest::checkParserVector);
    }

    @TestFactory
    Stream<DynamicTest> resolverVectors() {
        return vectorsIn("resolver", ConformanceSuiteTest::checkResolverVector);
    }

    @TestFactory
    Stream<DynamicTest> vocabularyVectors() {
        return vectorsIn("vocabulary", ConformanceSuiteTest::checkVocabularyVector);
    }

    private interface VectorCheck {
        void check(String bucket, Path subject, RecordValue sidecarBody) throws IOException;
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
                    .filter(p -> p.toString().endsWith(".tn") && !p.toString().endsWith("-expected.tn"))
                    .sorted()
                    .map(subject -> {
                        String slug = subject.getFileName().toString().replace(".tn", "");
                        Path tson = bucketDir.resolve(slug + "-expected.tn");
                        String name = layer + "/" + bucket + "/" + slug;
                        return DynamicTest.dynamicTest(name, () -> {
                            RecordValue sidecarBody = parseSidecarBody(tson);
                            check.check(bucket, subject, sidecarBody);
                        });
                    })
                    .toList()
                    .stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The sidecar is itself TSON: a document whose root value is a record. Parsed with the real TsonDataParser. */
    private static RecordValue parseSidecarBody(Path tsonPath) throws IOException {
        String text = readRaw(tsonPath);
        Document doc;
        try {
            doc = new TsonDataParser(text).parseDocument();
        } catch (RuntimeException e) {
            throw new AssertionError("sidecar " + tsonPath + " is not valid TSON: " + e.getMessage(), e);
        }
        return assertInstanceOf(RecordValue.class, doc.root().coreValue(),
                "sidecar root must be a record");
    }

    // ── Lexer-layer vectors ──────────────────────────────────────────────

    private static void checkLexerVector(String bucket, Path subject, RecordValue sidecar) throws IOException {
        String outcome = fieldText(sidecar, "outcome");
        if (hasField(sidecar, "encoding")) {
            checkEncodingVector(subject, sidecar, outcome);
            return;
        }
        String raw = resolvedRaw(subject, sidecar);
        switch (outcome) {
            case "valid" -> {
                List<Token> actual = new Lexer(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8))).tokenize();
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
            case "error" -> assertThrows(LexException.class,
                    () -> new Lexer(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8))).tokenize());
            default -> fail("unknown lexer-layer outcome: " + outcome);
        }
    }

    /**
     * A vector whose subject is <b>not plain UTF-8</b> (its sidecar says so with {@code encoding}), fed to
     * the lexer as the bytes on disk.
     *
     * <p>Every other vector is read into a {@code String} and re-encoded, which is harmless for text and
     * destroys exactly this kind of vector: the decode replaces the malformed bytes with U+FFFD before the
     * lexer ever sees them, so the test would assert that a *different* document is rejected. The suite's
     * own README says the subject is a raw file precisely because "several things this suite needs to test
     * only exist as raw bytes".
     *
     * <p>{@code utf-16}/{@code utf-32} are skipped rather than failed: §9.1 permits them and this
     * implementation reads only UTF-8, which is a gap in the implementation and not a vector to fail on.
     */
    private static void checkEncodingVector(Path subject, RecordValue sidecar, String outcome) throws IOException {
        String encoding = fieldText(sidecar, "encoding");
        Assumptions.assumeTrue("invalid-utf8".equals(encoding),
                "this implementation reads only UTF-8 (§9.1 permits utf-16/utf-32); vector encoding: " + encoding);
        byte[] raw = Files.readAllBytes(subject);
        switch (outcome) {
            case "error" -> assertThrows(LexException.class, () -> new Lexer(new ByteArrayInputStream(raw)).tokenize());
            case "valid" -> fail("an 'invalid-utf8' subject cannot lex cleanly: " + subject.getFileName());
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

    // ── TsonDataParser-layer vectors ─────────────────────────────────────────────

    private static void checkParserVector(String bucket, Path subject, RecordValue sidecar) throws IOException {
        String outcome = fieldText(sidecar, "outcome");
        String raw = resolvedRaw(subject, sidecar);
        switch (outcome) {
            case "valid" -> {
                Document actual = new TsonDataParser(raw).parseDocument();
                RecordValue expectedDoc = (RecordValue) fieldCore(sidecar, "document");
                assertDocumentMatches(expectedDoc, actual);
            }
            case "error" -> assertThrows(TsonParseException.class, () -> new TsonDataParser(raw).parseDocument());
            case "schema-document" -> assertThrows(TsonUnsupportedDocumentException.class,
                    () -> new TsonDataParser(raw).parseDocument());
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

    // ── Resolver-layer vectors ───────────────────────────────────────────

    private static void checkResolverVector(String bucket, Path subject, RecordValue sidecar) throws IOException {
        String outcome = fieldText(sidecar, "outcome");
        if (!outcome.equals("valid")) {
            fail("unknown resolver-layer outcome: " + outcome);
            return;
        }
        Document doc = new TsonDataParser(resolvedRaw(subject, sidecar)).parseDocument();
        TokenValue token = assertInstanceOf(TokenValue.class, doc.root().coreValue(),
                "resolver vector .tn must be a single bare token");
        BaseValue actual = BaseTypeResolver.resolve(token);
        RecordValue expected = (RecordValue) fieldCore(sidecar, "base-value");
        assertBaseValueMatches(expected, actual);
    }

    private static void assertBaseValueMatches(RecordValue expected, BaseValue actual) {
        String kind = fieldText(expected, "kind");
        switch (kind) {
            case "null" -> assertInstanceOf(BaseValue.NullValue.class, actual, "base-value kind 'null'");
            case "boolean" -> {
                BaseValue.BooleanValue bv = assertInstanceOf(BaseValue.BooleanValue.class, actual, "base-value kind 'boolean'");
                assertEquals(fieldText(expected, "value").equals("true"), bv.value(), "boolean value");
            }
            case "string" -> {
                BaseValue.StringValue sv = assertInstanceOf(BaseValue.StringValue.class, actual, "base-value kind 'string'");
                assertEquals(fieldText(expected, "text"), sv.text(), "string text");
            }
            case "number" -> {
                BaseValue.NumberValue nv = assertInstanceOf(BaseValue.NumberValue.class, actual, "base-value kind 'number'");
                assertNumberFormMatches((RecordValue) fieldValue(expected, "form").coreValue(), nv.form());
            }
            default -> fail("unknown expected base-value kind: " + kind);
        }
    }

    private static void assertNumberFormMatches(RecordValue expected, NumberForm actual) {
        String shape = fieldText(expected, "shape");
        switch (shape) {
            case "integer" -> {
                NumberForm.IntegerForm f = assertInstanceOf(NumberForm.IntegerForm.class, actual, "number-form shape 'integer'");
                assertEquals(fieldSignOrAbsent(expected), f.sign().orElse(null), "integer sign");
                assertEquals(fieldText(expected, "digits"), f.digits(), "integer digits");
            }
            case "based-integer" -> {
                NumberForm.BasedIntegerForm f = assertInstanceOf(NumberForm.BasedIntegerForm.class, actual, "number-form shape 'based-integer'");
                assertEquals(fieldSignOrAbsent(expected), f.sign().orElse(null), "based-integer sign");
                assertEquals(fieldRadix(expected), f.radix(), "based-integer radix");
                assertEquals(fieldText(expected, "digits"), f.digits(), "based-integer digits");
            }
            case "float" -> {
                NumberForm.FloatForm f = assertInstanceOf(NumberForm.FloatForm.class, actual, "number-form shape 'float'");
                assertEquals(fieldSignOrAbsent(expected), f.sign().orElse(null), "float sign");
                assertEquals(fieldTextOrAbsent(expected, "integer-part"), f.integerPart().orElse(null), "float integer-part");
                assertEquals(fieldTextOrAbsent(expected, "fraction-digits"), f.fractionDigits().orElse(null), "float fraction-digits");

                DataValue expExponent = fieldValue(expected, "exponent");
                boolean expectsExponent = !(expExponent.coreValue() instanceof AbsentValue);
                assertEquals(expectsExponent, f.exponent().isPresent(), "float exponent presence");
                if (expectsExponent) {
                    RecordValue expExpRecord = (RecordValue) expExponent.coreValue();
                    NumberForm.ExponentPart exp = f.exponent().orElseThrow();
                    assertEquals(fieldSignOrAbsent(expExpRecord), exp.sign().orElse(null), "exponent sign");
                    assertEquals(fieldText(expExpRecord, "digits"), exp.digits(), "exponent digits");
                }
            }
            case "special-value" -> {
                NumberForm.SpecialValueForm f = assertInstanceOf(NumberForm.SpecialValueForm.class, actual, "number-form shape 'special-value'");
                assertEquals(fieldSignOrAbsent(expected), f.sign().orElse(null), "special-value sign");
                String expKind = fieldText(expected, "kind");
                NumberForm.SpecialValueForm.Kind actKind = f.kind();
                assertEquals(expKind, switch (actKind) {
                    case NAN -> "nan";
                    case INFINITY -> "infinity";
                }, "special-value kind");
            }
            default -> fail("unknown expected number-form shape: " + shape);
        }
    }

    private static NumberForm.Sign fieldSignOrAbsent(RecordValue r) {
        String s = fieldTextOrAbsent(r, "sign");
        if (s == null) return null;
        return switch (s) {
            case "plus" -> NumberForm.Sign.PLUS;
            case "minus" -> NumberForm.Sign.MINUS;
            default -> throw new AssertionError("unknown sign literal: " + s);
        };
    }

    private static NumberForm.BasedIntegerForm.Radix fieldRadix(RecordValue r) {
        return switch (fieldText(r, "radix")) {
            case "hex" -> NumberForm.BasedIntegerForm.Radix.HEX;
            case "octal" -> NumberForm.BasedIntegerForm.Radix.OCTAL;
            case "binary" -> NumberForm.BasedIntegerForm.Radix.BINARY;
            default -> throw new AssertionError("unknown radix literal: " + fieldText(r, "radix"));
        };
    }

    // ── Vocabulary-layer vectors (§5) ────────────────────────────────────

    /**
     * The .tn is a {@code !type-ref token} data-value. On a {@code valid} vector, most families
     * assert {@code value} (a plain decimal string) against {@link AtomType#read(TokenValue, Class)}
     * with {@link BigDecimal} as the target -- host-representation-neutral, matching the suite's own
     * resolver-vector philosophy (§5.2 leaves the concrete bound type implementation-defined), and
     * the one target every {@code BigDecimal}-representable family shares. {@code rational} and
     * {@code complex} have no natural {@code BigDecimal} representation ({@link Rational}/{@link
     * Complex} are each other atom's *only* legitimate target, per {@link AtomType}'s default {@code
     * read(token, target)}), so those two are asserted against their own natural type instead --
     * {@code rational}'s {@code value} is a {@code "numerator/denominator"} string parsed directly
     * into a {@link Rational} (comparable via its own value-based {@code equals}); {@code complex}'s
     * {@code value} is a {@code { real: ... imaginary: ... }} record, each part compared via {@link
     * BigDecimal#compareTo} the same way the {@code BigDecimal}-based families are. {@code base64}/
     * {@code base64url}/{@code base32}/{@code hex} (§5.3) have no {@code BigDecimal} representation
     * either -- their {@code value} is a plain hex string decoded via {@link HexFormat} and compared
     * against the atom's {@code byte[]} result with {@link
     * org.junit.jupiter.api.Assertions#assertArrayEquals}, not {@code equals} (arrays don't have
     * value-based {@code equals} in Java). On an {@code error} vector, {@code category} is
     * additionally checked against which of {@link
     * AtomParseException}/{@link AtomValidationException} was actually thrown, per this
     * §5.2/§8.1's own split, which the test suite's README still flags as unsettled: a token the atom's
     * grammar rejects is a resolver error, a parsed value violating its range a validation error.
     */
    private static void checkVocabularyVector(String bucket, Path subject, RecordValue sidecar) throws IOException {
        String outcome = fieldText(sidecar, "outcome");
        Document doc = new TsonDataParser(resolvedRaw(subject, sidecar)).parseDocument();
        DataValue root = doc.root();
        String typeRef = root.typeRef().orElseThrow(
                () -> new AssertionError("vocabulary vector .tn must carry a type-ref"));
        TokenValue token = assertInstanceOf(TokenValue.class, root.coreValue(),
                "vocabulary vector .tn must be a type-ref'd token");
        AtomType<?> atomType = BuiltinTypeVocabulary.lookup(typeRef)
                .orElseThrow(() -> new AssertionError("unrecognized type-ref in vocabulary vector: " + typeRef));

        switch (outcome) {
            case "valid" -> checkValidVocabularyVector(typeRef, atomType, token, sidecar);
            case "error" -> {
                String category = fieldText(sidecar, "category");
                AtomTypeException thrown = assertThrows(AtomTypeException.class, () -> atomType.read(token));
                switch (category) {
                    case "resolver" -> assertInstanceOf(AtomParseException.class, thrown,
                            "category 'resolver' -> AtomParseException");
                    case "validation" -> assertInstanceOf(AtomValidationException.class, thrown,
                            "category 'validation' -> AtomValidationException");
                    default -> fail("unexpected category for vocabulary-layer error: " + category);
                }
            }
            default -> fail("unknown vocabulary-layer outcome: " + outcome);
        }
    }

    private static void checkValidVocabularyVector(String typeRef, AtomType<?> atomType, TokenValue token, RecordValue sidecar) {
        switch (typeRef) {
            case "rational" -> {
                Rational actual = (Rational) atomType.read(token, Rational.class);
                assertEquals(parseRational(fieldText(sidecar, "value")), actual, "vocabulary value");
            }
            case "complex" -> {
                Complex actual = (Complex) atomType.read(token, Complex.class);
                RecordValue expected = (RecordValue) fieldCore(sidecar, "value");
                assertEquals(0, new BigDecimal(fieldText(expected, "real")).compareTo(actual.real()), "complex real part");
                assertEquals(0, new BigDecimal(fieldText(expected, "imaginary")).compareTo(actual.imaginary()), "complex imaginary part");
            }
            case "uuid" -> {
                UUID actual = (UUID) atomType.read(token, UUID.class);
                assertEquals(UUID.fromString(fieldText(sidecar, "value")), actual, "vocabulary value");
            }
            case "base64", "base64url", "base32", "hex" -> {
                byte[] actual = (byte[]) atomType.read(token, byte[].class);
                assertArrayEquals(HexFormat.of().parseHex(fieldText(sidecar, "value")), actual, "vocabulary value");
            }
            case "date" -> {
                LocalDate actual = (LocalDate) atomType.read(token, LocalDate.class);
                assertEquals(LocalDate.parse(fieldText(sidecar, "value")), actual, "vocabulary value");
            }
            case "time" -> {
                OffsetTime actual = (OffsetTime) atomType.read(token, OffsetTime.class);
                assertEquals(OffsetTime.parse(fieldText(sidecar, "value")), actual, "vocabulary value");
            }
            case "datetime" -> {
                OffsetDateTime actual = (OffsetDateTime) atomType.read(token, OffsetDateTime.class);
                assertEquals(OffsetDateTime.parse(fieldText(sidecar, "value")), actual, "vocabulary value");
            }
            case "duration" -> {
                IsoDuration actual = (IsoDuration) atomType.read(token, IsoDuration.class);
                RecordValue expected = (RecordValue) fieldCore(sidecar, "value");
                assertEquals(Period.parse(fieldText(expected, "period")), actual.calendarPart(), "duration calendar part");
                assertEquals(Duration.parse(fieldText(expected, "clock")), actual.clockPart(), "duration clock part");
            }
            case "uri" -> {
                URI actual = (URI) atomType.read(token, URI.class);
                assertEquals(URI.create(fieldText(sidecar, "value")), actual, "vocabulary value");
            }
            case "ipv4" -> {
                Inet4Address actual = (Inet4Address) atomType.read(token, Inet4Address.class);
                assertEquals(InetAddress.ofLiteral(fieldText(sidecar, "value")), actual, "vocabulary value");
            }
            case "ipv6" -> {
                // Unlike ipv4, value is the plain hex string of the 16 raw address bytes (the same
                // convention as the binary family), not a textual IPv6 literal -- InetAddress
                // itself silently collapses an IPv4-mapped 16-byte pattern to an Inet4Address, so
                // there's no single JDK parse this suite could trust as a neutral oracle here.
                Inet6Address actual = (Inet6Address) atomType.read(token, Inet6Address.class);
                assertArrayEquals(HexFormat.of().parseHex(fieldText(sidecar, "value")), actual.getAddress(),
                        "vocabulary value");
            }
            case "text", "cidr4", "cidr6", "mac", "email" -> {
                // The atoms whose host value is the authored text itself, so the oracle is a plain string
                // compare with no parse in between. Deliberately not folded into the numeric default arm
                // below. Three reasons converge here: `text` is text by definition (§5.5, "the host value is
                // the token's text"), while `cidr4`/`cidr6`/`mac` keep their text because Java has no type to
                // map onto (see Cidr4Parser/MacParser) and `email` because the address shape is the contract.
                String actual = (String) atomType.read(token, String.class);
                assertEquals(fieldText(sidecar, "value"), actual, "vocabulary value");
            }
            default -> {
                BigDecimal actual = (BigDecimal) atomType.read(token, BigDecimal.class);
                BigDecimal expected = new BigDecimal(fieldText(sidecar, "value"));
                assertEquals(0, expected.compareTo(actual),
                        "vocabulary value: expected " + expected + ", got " + actual);
            }
        }
    }

    private static Rational parseRational(String text) {
        String[] parts = text.split("/", 2);
        return new Rational(new BigInteger(parts[0]), new BigInteger(parts[1]));
    }

    // ── Dynamic meta/import splicing (see resolvedRaw) ───────────────────

    /**
     * Proves {@link #resolvedRaw} both splices the right text, in the right place, and produces a
     * document that genuinely resolves against the real bundled meta.tn/core.tn chain -- not just
     * something that looks plausible. Self-contained: doesn't depend on the sibling test-suite repo
     * being checked out.
     */
    @Test
    void resolvedRawSplicesRealDirectivesThatActuallyResolve(@TempDir Path tempDir) throws IOException {
        Path subject = tempDir.resolve("synthetic.tn");
        Files.writeString(subject,
                "!!id:\"https://tson.io/test-suite/schema/valid/synthetic.tn\"\n{ my_int => integer }");
        RecordValue sidecar = (RecordValue) new TsonDataParser("{ meta: \"meta.tn\" import: [\"core.tn\"] }")
                .parseDocument().root().coreValue();

        String resolved = resolvedRaw(subject, sidecar);

        assertEquals(
                "!!id:\"https://tson.io/test-suite/schema/valid/synthetic.tn\"\n"
                        + "!!meta:\"" + TsonBundledSchemas.META_ID + "\"\n"
                        + "!!import:\"" + TsonBundledSchemas.CORE_ID + "\"\n"
                        + "{ my_int => integer }",
                resolved, "!!meta/!!import spliced right after !!id, in header order");

        SchemaDocument schemaDocument = new TsonSchemaParser(resolved).parseSchemaDocument();
        // Resolution (unlike compilation) always needs an object-binding-mode governing-meta reader,
        // regardless of what mode the caller eventually wants -- see Tson's own class Javadoc.
        // Same bootstrap sequence TsonConfig#build uses: meta-kernel has to be resolved and
        // registered explicitly before anything that transitively !!imports it (meta.tn, here) can
        // register itself -- see TsonBundledSchemas's own class Javadoc for why.
        TsonCompiledMetaRegistry compiledRegistry =
                new TsonCompiledMetaRegistry(SchemaMetaNameBinder.defaultContext(), TsonBundledSchemas::fetch);
        TsonCompiledSchemaLoader loader = compiledRegistry;
        SchemaDocument metaKernelDocument = new TsonSchemaParser(
                TsonBundledSchemas.fetch(TsonBundledSchemas.META_KERNEL_ID)).parseSchemaDocument();
        TsonSchema resolvedMetaKernel = new TsonSchemaResolver(loader).resolveSchema(metaKernelDocument);
        compiledRegistry.register(resolvedMetaKernel, loader.loadMeta(TsonBundledSchemas.META_KERNEL_ID));

        TsonSchema resolvedSchema = new TsonSchemaResolver(loader).resolveSchema(schemaDocument);
        assertTrue(resolvedSchema.entries().containsKey("my_int"),
                "my_int should resolve as a bare reference to core.tn's own integer, reachable via the real spliced !!import");
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

    /** Unlike {@link #fieldTextOrAbsent}, for a field that may be genuinely absent from the record entirely (not present with value {@code _}) -- what an optional sidecar field like {@code meta}/{@code import} (see {@link #resolvedRaw}) actually looks like when unused. */
    private static boolean hasField(RecordValue r, String name) {
        return r.fields().stream().anyMatch(f -> f.name().equals(name));
    }

    /** Each element of an array-of-tokens field (e.g. {@code import}), as plain text. */
    private static List<String> fieldTextArray(RecordValue r, String name) {
        ArrayValue array = (ArrayValue) fieldCore(r, name);
        List<String> result = new ArrayList<>();
        for (ScopedValue element : array.elements()) {
            result.add(assertInstanceOf(TokenValue.class, element.value().coreValue(),
                    "field '" + name + "' element").text());
        }
        return result;
    }

    private static String readRaw(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    /**
     * {@code subject}'s own raw text, with a {@code meta}/{@code import} sidecar field (see {@link
     * #SCHEMA_SHORT_NAMES}) resolved to its real bundled-schema identity and spliced into the
     * document's own header as a genuine {@code !!meta}/{@code !!import} directive -- a pure no-op
     * (returns {@link #readRaw} unchanged) when the sidecar declares neither field, which is every
     * vector as of this writing.
     *
     * <p>Can't be a blind prepend: {@code TsonSchemaParser}'s own header grammar is a fixed
     * sequence -- optional {@code !!id}, then exactly one {@code !!meta} ("immediately after
     * {@code !!id} if present"), then zero or more {@code !!import} -- so the resolved directive
     * block is inserted right after the subject's own {@code !!id} line (every real schema-document
     * subject already writes one), or at the very start if the subject has none.
     */
    private static String resolvedRaw(Path subject, RecordValue sidecar) throws IOException {
        String raw = readRaw(subject);
        if (!hasField(sidecar, "meta")) {
            return raw;
        }
        StringBuilder directives = new StringBuilder();
        directives.append("!!meta:\"").append(resolveShortName(fieldText(sidecar, "meta"))).append("\"\n");
        if (hasField(sidecar, "import")) {
            for (String importName : fieldTextArray(sidecar, "import")) {
                directives.append("!!import:\"").append(resolveShortName(importName)).append("\"\n");
            }
        }
        int insertAt;
        if (raw.startsWith("!!id:")) {
            int newline = raw.indexOf('\n');
            insertAt = (newline == -1) ? raw.length() : newline + 1;
        } else {
            insertAt = 0;
        }
        return raw.substring(0, insertAt) + directives + raw.substring(insertAt);
    }

    private static String resolveShortName(String shortName) {
        String resolved = SCHEMA_SHORT_NAMES.get(shortName);
        if (resolved == null) {
            throw new AssertionError("unknown schema short name '" + shortName
                    + "' -- expected one of " + SCHEMA_SHORT_NAMES.keySet());
        }
        return resolved;
    }
}
