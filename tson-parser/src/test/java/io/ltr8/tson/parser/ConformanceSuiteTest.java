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
import io.ltr8.tson.parser.resolver.BaseTypeResolver;
import io.ltr8.tson.parser.resolver.BaseValue;
import io.ltr8.tson.parser.resolver.NumberForm;
import io.ltr8.tson.parser.resolver.vocab.AtomParseException;
import io.ltr8.tson.parser.resolver.vocab.AtomType;
import io.ltr8.tson.parser.resolver.vocab.AtomTypeException;
import io.ltr8.tson.parser.resolver.vocab.AtomValidationException;
import io.ltr8.tson.parser.resolver.vocab.BuiltinTypeVocabulary;
import io.ltr8.tson.parser.resolver.vocab.Complex;
import io.ltr8.tson.parser.resolver.vocab.IsoDuration;
import io.ltr8.tson.parser.resolver.vocab.Rational;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

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
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs every vector in the sibling {@code ltr8-io-tson-test-suite} repo (see its own README for
 * the vector/sidecar format) against this implementation's real {@link Lexer}, {@link Parser},
 * {@link BaseTypeResolver}, and {@link BuiltinTypeVocabulary}.
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

    @TestFactory
    Stream<DynamicTest> resolverVectors() {
        return vectorsIn("resolver", ConformanceSuiteTest::checkResolverVector);
    }

    @TestFactory
    Stream<DynamicTest> vocabularyVectors() {
        return vectorsIn("vocabulary", ConformanceSuiteTest::checkVocabularyVector);
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

    // ── Resolver-layer vectors ───────────────────────────────────────────

    private static void checkResolverVector(String bucket, Path tn1, RecordValue sidecar) throws IOException {
        String outcome = fieldText(sidecar, "outcome");
        if (!outcome.equals("valid")) {
            fail("unknown resolver-layer outcome: " + outcome);
            return;
        }
        Document doc = new Parser(readRaw(tn1)).parseDocument();
        TokenValue token = assertInstanceOf(TokenValue.class, doc.root().coreValue(),
                "resolver vector .tn1 must be a single bare token");
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
     * The .tn1 is a {@code !type-ref token} data-value. On a {@code valid} vector, most families
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
     * implementation's own interpretation of the §5.2/§8.1 categorization question the test suite's
     * own README flags as unsettled (see SPEC-FEEDBACK.md #8): parse-shape failures as {@code
     * resolver}, range/constraint failures as {@code validation}.
     */
    private static void checkVocabularyVector(String bucket, Path tn1, RecordValue sidecar) throws IOException {
        String outcome = fieldText(sidecar, "outcome");
        Document doc = new Parser(readRaw(tn1)).parseDocument();
        DataValue root = doc.root();
        String typeRef = root.typeRef().orElseThrow(
                () -> new AssertionError("vocabulary vector .tn1 must carry a type-ref"));
        TokenValue token = assertInstanceOf(TokenValue.class, root.coreValue(),
                "vocabulary vector .tn1 must be a type-ref'd token");
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
