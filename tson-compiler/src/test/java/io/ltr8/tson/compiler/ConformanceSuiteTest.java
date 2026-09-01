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
import io.ltr8.tson.compiler.lexer.Xid;
import io.ltr8.tson.compiler.base.BaseTypeResolver;
import io.ltr8.tson.compiler.base.BaseValue;
import io.ltr8.tson.compiler.base.NumberForm;
import io.ltr8.tson.compiler.atom.AtomParseException;
import io.ltr8.tson.compiler.atom.AtomType;
import io.ltr8.tson.compiler.atom.AtomValidationException;
import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.compiler.atom.BuiltinTypeVocabulary;
import io.ltr8.tson.compiler.atom.Complex;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.suite.Sidecar;
import io.ltr8.tson.suite.Vectors;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.IsoDuration;
import io.ltr8.tson.schema.meta.Rational;
import io.ltr8.tson.tree.TsonAbsent;
import io.ltr8.tson.tree.TsonArray;
import io.ltr8.tson.tree.TsonAtom;
import io.ltr8.tson.tree.TsonMap;
import io.ltr8.tson.tree.TsonRecord;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
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

import static io.ltr8.tson.suite.Sidecar.fieldCore;
import static io.ltr8.tson.suite.Sidecar.fieldText;
import static io.ltr8.tson.suite.Sidecar.fieldTextOrAbsent;
import static io.ltr8.tson.suite.Sidecar.fieldValue;
import static io.ltr8.tson.suite.Sidecar.hasField;
import static io.ltr8.tson.suite.Sidecar.outcomeOf;
import static io.ltr8.tson.suite.Sidecar.outcomePayload;
import static io.ltr8.tson.suite.Sidecar.soleField;
import static io.ltr8.tson.suite.Sidecar.subjectBytes;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs every vector in the shared {@code ltr8-io-tson-test-suite} corpus (see its own README for
 * the vector/sidecar format) against this implementation's real {@link Lexer}, {@link TsonDataParser},
 * {@link BaseTypeResolver}, and {@link BuiltinTypeVocabulary}.
 *
 * <p><b>{@code RUNNER.md} in that repo is normative for this class.</b> It is what feeds a subject as
 * raw bytes rather than a re-encoded string, checks an {@code error} vector's §8.1 category at every
 * layer rather than only the vocabulary one, and limits what may be skipped -- rules that exist
 * because this runner and the TypeScript port's had already drifted apart on the first two.
 *
 * <p>This is deliberately separate from {@link io.ltr8.tson.compiler.lexer.LexerTest} and
 * {@link TsonDataParserTest}: those are fine-grained unit tests of individual grammar rules with
 * assertion messages that point at exactly what broke. This is a conformance/integration test
 * against an external, language-agnostic, spec-derived fixture set shared with (potentially)
 * other implementations -- it exists to catch drift between this implementation and the spec,
 * not to pinpoint which internal rule regressed.
 *
 * <p>The suite checkout is located by {@link SuiteCheckout}: a sibling working copy, or the pinned
 * copy {@code scripts/fetch-references.sh} fetches into {@code .references/}, which is what CI runs.
 * If neither is present -- a bare clone, or a fork that cannot reach the suite repo -- every
 * {@code @TestFactory} here is skipped via {@link Assumptions}, not failed.
 *
 * <p><b>A skip is not a pass, and CI must not take one.</b> An aborted {@code Assumptions} run
 * reads green, so a CI that never checked the suite out reported success while running no vector at
 * all. The fetch step exists to close that: the reference implementation is measured against the
 * shared corpus on every push, like the ports are.
 */
class ConformanceSuiteTest {

    @TestFactory
    Stream<DynamicTest> lexerVectors() {
        return Vectors.in("class1", "lexer", ConformanceSuiteTest::checkLexerVector);
    }

    @TestFactory
    Stream<DynamicTest> parserVectors() {
        return Vectors.in("class1", "parser", ConformanceSuiteTest::checkParserVector);
    }

    @TestFactory
    Stream<DynamicTest> readerVectors() {
        return Vectors.in("class1", "reader", ConformanceSuiteTest::checkReaderVector);
    }

    @TestFactory
    Stream<DynamicTest> resolverVectors() {
        return Vectors.in("class1", "resolver", ConformanceSuiteTest::checkResolverVector);
    }

    @TestFactory
    Stream<DynamicTest> vocabularyVectors() {
        return Vectors.in("class1", "vocabulary", ConformanceSuiteTest::checkVocabularyVector);
    }

    // ── Lexer-layer vectors ──────────────────────────────────────────────

    private static void checkLexerVector(String bucket, Path subject, RecordValue sidecar) throws IOException {
        if (hasField(sidecar, "encoding")) {
            checkEncodingVector(subject, sidecar);
            return;
        }
        byte[] raw = subjectBytes(subject, sidecar);
        switch (outcomeOf(sidecar)) {
            case "valid" -> {
                List<Token> actual = new Lexer(new ByteArrayInputStream(raw)).tokenize();
                actual.removeIf(t -> t.type() == TokenType.EOF);
                ArrayValue expectedTokens = (ArrayValue) fieldCore(outcomePayload(sidecar), "tokens");
                assertEquals(expectedTokens.elements().size(), actual.size(), "token count");
                for (int i = 0; i < actual.size(); i++) {
                    RecordValue expTok = (RecordValue) expectedTokens.elements().get(i).value().coreValue();
                    String expKind = fieldText(expTok, "kind");
                    String expText = fieldText(expTok, "text");
                    assertEquals(expKind, kindName(actual.get(i).type()), "token[" + i + "].kind");
                    assertEquals(expText, actual.get(i).text(), "token[" + i + "].text");
                }
            }
            case "error" -> assertThrows(errorClassFor(sidecar),
                    () -> new Lexer(new ByteArrayInputStream(raw)).tokenize());
            default -> fail("unknown lexer-layer outcome: " + outcomeOf(sidecar));
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
    private static void checkEncodingVector(Path subject, RecordValue sidecar) throws IOException {
        String encoding = fieldText(sidecar, "encoding");
        Assumptions.assumeTrue("invalid-utf8".equals(encoding),
                "this implementation reads only UTF-8 (§9.1 permits utf-16/utf-32); vector encoding: " + encoding);
        byte[] raw = Files.readAllBytes(subject);
        switch (outcomeOf(sidecar)) {
            case "error" -> assertThrows(errorClassFor(sidecar),
                    () -> new Lexer(new ByteArrayInputStream(raw)).tokenize());
            case "valid" -> fail("an 'invalid-utf8' subject cannot lex cleanly: " + subject.getFileName());
            default -> fail("unknown lexer-layer outcome: " + outcomeOf(sidecar));
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
        byte[] raw = subjectBytes(subject, sidecar);
        switch (outcomeOf(sidecar)) {
            case "valid" -> {
                Document actual = new TsonDataParser(new ByteArrayInputStream(raw)).parseDocument();
                RecordValue expectedDoc = (RecordValue) fieldCore(outcomePayload(sidecar), "document");
                assertDocumentMatches(expectedDoc, actual);
            }
            case "error" -> assertThrows(errorClassFor(sidecar),
                    () -> new TsonDataParser(new ByteArrayInputStream(raw)).parseDocument());
            case "schema-document" -> assertThrows(TsonUnsupportedDocumentException.class,
                    () -> new TsonDataParser(new ByteArrayInputStream(raw)).parseDocument());
            default -> fail("unknown parser-layer outcome: " + outcomeOf(sidecar));
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
        RecordValue.Field member = soleField(expected, "core-value");
        String kind = member.name();
        RecordValue payload = kind.equals("absent") || kind.equals("empty-brace")
                ? null
                : (RecordValue) member.value().value().coreValue();
        switch (kind) {
            case "token" -> {
                TokenValue tv = assertInstanceOf(TokenValue.class, actual, "core-value kind 'token'");
                String expForm = fieldText(payload, "form");
                String actForm = switch (tv.form()) {
                    case UNQUOTED -> "unquoted";
                    case SINGLE_LINE_QUOTED -> "single-line";
                    case MULTI_LINE_QUOTED -> "multi-line";
                };
                assertEquals(expForm, actForm, "token form");
                assertEquals(fieldText(payload, "text"), tv.text(), "token text");
            }
            case "absent" -> assertInstanceOf(AbsentValue.class, actual, "core-value kind 'absent'");
            case "empty-brace" -> assertInstanceOf(EmptyBrace.class, actual, "core-value kind 'empty-brace'");
            case "record" -> {
                RecordValue rv = assertInstanceOf(RecordValue.class, actual, "core-value kind 'record'");
                ArrayValue expFields = (ArrayValue) fieldCore(payload, "fields");
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
                ArrayValue expEntries = (ArrayValue) fieldCore(payload, "entries");
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
                ArrayValue expElements = (ArrayValue) fieldCore(payload, "elements");
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

    // ── Reader-layer vectors ─────────────────────────────────────────────

    /**
     * The layer §1.2 leaves nothing below: neither tier dedupes fields or keys, resolves an empty
     * brace, or interprets token text, so §2.5's field uniqueness, §2.6's key identity, §2.8's
     * empty brace and §2.9's absent-key restriction have no other layer that can fail on them.
     * A schemaless read is where a Class 1 document gets its verdict, so that is what runs here.
     *
     * <p><b>An error vector's subject must parse.</b> That is what makes it a reader-layer vector
     * rather than a parser-layer one, and asserting it is how this layer answers {@code RUNNER.md}
     * rule 3: the stated {@code resolver} category means the reader rejected the document, not the
     * lexer or the parser, and a vector that turned out to be a parse error would otherwise pass
     * here for the wrong reason.
     */
    private static void checkReaderVector(String bucket, Path subject, RecordValue sidecar) throws IOException {
        byte[] raw = subjectBytes(subject, sidecar);
        switch (outcomeOf(sidecar)) {
            case "valid" -> {
                TsonValue actual = new TsonTreeReader().read(new ByteArrayInputStream(raw));
                assertReaderValueMatches((RecordValue) fieldCore(outcomePayload(sidecar), "value"), actual);
            }
            case "error" -> {
                new TsonDataParser(new ByteArrayInputStream(raw)).parseDocument();
                List<Diagnostic> reported = new ArrayList<>();
                new TsonTreeReader().withDiagnostics(reported::add).read(new ByteArrayInputStream(raw));
                assertTrue(!reported.isEmpty(),
                        "the document parses, so the reader is what must reject it -- none reported");
                reported.forEach(diagnostic -> assertFalse(isPolicyRefusal(diagnostic.code()),
                        "an error vector must not be satisfied by a §8.2 policy refusal: " + diagnostic));
            }
            case "refused" -> checkRefusedVector(raw, sidecar);
            default -> fail("unknown reader-layer outcome: " + outcomeOf(sidecar));
        }
    }

    /**
     * §8.1's fifth outcome: the document is <b>refused by this processor</b> under one of §8.2's
     * name-hygiene rules, and is not invalid.
     *
     * <p><b>The refusal must be distinguishable</b>, which is the whole reason the outcome exists.
     * §8.2 keeps these checks out of validity because each reads data the Unicode Consortium declines
     * to freeze, so a verdict can change under a routine UCD refresh while a content-addressed
     * document must mean the same thing forever. A processor reporting a confusable pair the way it
     * reports an out-of-range integer has not passed the vector, so this asserts the code is one of
     * the two that mean policy and none of the four categories.
     *
     * <p><b>And the versions must match.</b> §8.2 says two conforming implementations may legitimately
     * disagree on a refusal, with the UTS #39 data version the only thing that explains it -- so a
     * vector computed against another version is {@code RUNNER.md} rule 5's fourth legitimate skip,
     * reported rather than silent. A vector at <em>this</em> version is never skippable.
     */
    private static void checkRefusedVector(byte[] raw, RecordValue sidecar) throws IOException {
        RecordValue refusal = outcomePayload(sidecar);
        String stated = fieldText(refusal, "unicode");
        Assumptions.assumeTrue(Xid.UNICODE_VERSION.equals(stated),
                "vector computed against UTS #39 data for Unicode " + stated + "; this implementation "
                        + "carries " + Xid.UNICODE_VERSION + " (§8.2: two implementations may legitimately "
                        + "disagree, and the version is what explains it)");

        // A refusal is not a parse failure: the document is well-formed and the reader refuses it.
        new TsonDataParser(new ByteArrayInputStream(raw)).parseDocument();
        List<Diagnostic> reported = new ArrayList<>();
        new TsonTreeReader().withDiagnostics(reported::add).read(new ByteArrayInputStream(raw));

        assertTrue(reported.stream().anyMatch(diagnostic -> isPolicyRefusal(diagnostic.code())),
                "expected a §8.2 policy refusal (" + fieldText(refusal, "mechanism") + "); got " + reported);
        reported.forEach(diagnostic -> assertTrue(isPolicyRefusal(diagnostic.code()),
                "a refused document must not also be reported invalid -- §8.2's refusal MUST NOT be any "
                        + "of §8.1's four categories: " + diagnostic));
        assertRefusalMatches(refusal, reported);
    }

    /**
     * The code a refusal by the rule the vector names reports.
     *
     * <p><b>An explicit table, not a string transformation.</b> The corpus spells §8.2's own headings
     * ({@code sidecar-common.tn}'s {@code hygiene_mechanism}: {@code skeleton-distinctness},
     * {@code identifier-status}, {@code restriction-level}), where this implementation names each code for
     * what it <em>found</em> -- §8.2's vocabulary is exact and is jargon a consumer reading an error body
     * cannot decode. The two vocabularies are deliberately different, so the mapping is written down.
     */
    private static Diagnostic.Code statedRule(RecordValue refusal) {
        String stated = fieldText(refusal, "mechanism");
        return switch (stated) {
            case "skeleton-distinctness" -> Diagnostic.Code.CONFUSABLE_NAMES;
            case "identifier-status" -> Diagnostic.Code.RESTRICTED_CHARACTER;
            case "restriction-level" -> Diagnostic.Code.RESTRICTED_SCRIPT;
            default -> throw new AssertionError("unknown §8.2 name-hygiene rule in a vector: " + stated);
        };
    }

    /**
     * <b>The vector names the rule it exercises, so the refusal must report the matching code</b> -- one
     * code per rule, since the three want three different remedies and a runner checking only "some refusal
     * happened" would pass a processor that refused for the wrong reason.
     *
     * <p><b>The data version §8.2 requires a refusal to name is the processor's, not the diagnostic's</b>
     * ({@link TsonUnicodePolicy#dataVersion()}, which the caller has already matched against the vector's
     * own {@code unicode} field before running it -- a version this implementation does not carry is a
     * legitimate skip). It is constant for every refusal in a run, so it is stated once beside the
     * diagnostics rather than stamped onto each of them.
     */
    private static void assertRefusalMatches(RecordValue refusal, List<Diagnostic> reported) {
        assertTrue(reported.stream().anyMatch(d -> d.code() == statedRule(refusal)),
                () -> "vector names " + fieldText(refusal, "mechanism") + "; got " + reported);
    }

    /**
     * The three codes that mean <em>refused under a stated policy</em> rather than <em>invalid</em>, one per
     * §8.2 rule. Every other code is a verdict on the document, which is exactly what a refusal is not.
     */
    private static boolean isPolicyRefusal(Diagnostic.Code code) {
        return code == Diagnostic.Code.CONFUSABLE_NAMES || code == Diagnostic.Code.RESTRICTED_CHARACTER
                || code == Diagnostic.Code.RESTRICTED_SCRIPT;
    }

    private static void assertReaderValueMatches(RecordValue expected, TsonValue actual) {
        RecordValue.Field member = soleField(expected, "reader-value");
        CoreValue payload = member.value().value().coreValue();
        switch (member.name()) {
            case "absent" -> assertInstanceOf(TsonAbsent.class, actual, "reader-value 'absent'");
            case "atom" -> assertAtomMatches((RecordValue) payload,
                    assertInstanceOf(TsonAtom.class, actual, "reader-value 'atom'"));
            case "record" -> {
                TsonRecord record = assertInstanceOf(TsonRecord.class, actual, "reader-value 'record'");
                ArrayValue expFields = (ArrayValue) fieldCore((RecordValue) payload, "fields");
                assertEquals(expFields.elements().size(), record.fields().size(), "record field count");
                List<Map.Entry<String, TsonValue>> actualFields = new ArrayList<>(record.fields().entrySet());
                for (int i = 0; i < actualFields.size(); i++) {
                    RecordValue expField = (RecordValue) expFields.elements().get(i).value().coreValue();
                    assertEquals(fieldText(expField, "name"), actualFields.get(i).getKey(),
                            "record field[" + i + "].name");
                    assertReaderValueMatches((RecordValue) fieldCore(expField, "value"),
                            actualFields.get(i).getValue());
                }
            }
            case "map" -> {
                TsonMap map = assertInstanceOf(TsonMap.class, actual, "reader-value 'map'");
                ArrayValue expEntries = (ArrayValue) fieldCore((RecordValue) payload, "entries");
                assertEquals(expEntries.elements().size(), map.entries().size(), "map entry count");
                for (int i = 0; i < map.entries().size(); i++) {
                    RecordValue expEntry = (RecordValue) expEntries.elements().get(i).value().coreValue();
                    assertReaderValueMatches((RecordValue) fieldCore(expEntry, "key"), map.entries().get(i).key());
                    assertReaderValueMatches((RecordValue) fieldCore(expEntry, "value"), map.entries().get(i).value());
                }
            }
            case "array" -> {
                TsonArray array = assertInstanceOf(TsonArray.class, actual, "reader-value 'array'");
                ArrayValue expElements = (ArrayValue) fieldCore((RecordValue) payload, "elements");
                assertEquals(expElements.elements().size(), array.elements().size(), "array element count");
                for (int i = 0; i < array.elements().size(); i++) {
                    assertReaderValueMatches(
                            (RecordValue) expElements.elements().get(i).value().coreValue(),
                            array.elements().get(i));
                }
            }
            default -> fail("unknown reader-value kind: " + member.name());
        }
    }

    /**
     * A leaf, named by the base type §4 resolved it to. A number is compared by numeric value rather
     * than by spelling: §4.3 leaves the host type an implementation concern, so what a vector may
     * state is the value, not which of its spellings the document wrote.
     */
    private static void assertAtomMatches(RecordValue expected, TsonAtom actual) {
        RecordValue.Field member = soleField(expected, "reader atom");
        String text = ((TokenValue) member.value().value().coreValue()).text();
        switch (member.name()) {
            case "boolean" -> assertEquals(Boolean.parseBoolean(text), actual.value(), "boolean atom");
            case "string" -> assertEquals(text, String.valueOf(actual.value()), "string atom");
            case "number" -> assertEquals(0, new BigDecimal(text).compareTo(new BigDecimal(String.valueOf(actual.value()))),
                    "number atom: expected " + text + ", got " + actual.value());
            default -> fail("unknown reader atom kind: " + member.name());
        }
    }

    // ── Resolver-layer vectors ───────────────────────────────────────────

    private static void checkResolverVector(String bucket, Path subject, RecordValue sidecar) throws IOException {
        // §4 never rejects a token, so this layer has one outcome and the sidecar carries `valid`
        // as a plain field -- a group of one is not a group (§5.11's two-member minimum).
        Document doc = new TsonDataParser(new ByteArrayInputStream(subjectBytes(subject, sidecar))).parseDocument();
        TokenValue token = assertInstanceOf(TokenValue.class, doc.root().coreValue(),
                "resolver vector .tn must be a single bare token");
        BaseValue actual = BaseTypeResolver.resolve(token);
        RecordValue valid = (RecordValue) fieldCore(sidecar, "valid");
        assertBaseValueMatches((RecordValue) fieldCore(valid, "base-value"), actual);
    }

    private static void assertBaseValueMatches(RecordValue expected, BaseValue actual) {
        RecordValue.Field member = soleField(expected, "base-value");
        CoreValue payload = member.value().value().coreValue();
        switch (member.name()) {
            case "null" -> assertInstanceOf(BaseValue.NullValue.class, actual, "base-value kind 'null'");
            case "boolean" -> {
                BaseValue.BooleanValue bv = assertInstanceOf(BaseValue.BooleanValue.class, actual, "base-value kind 'boolean'");
                assertEquals(((TokenValue) payload).text().equals("true"), bv.value(), "boolean value");
            }
            case "string" -> {
                BaseValue.StringValue sv = assertInstanceOf(BaseValue.StringValue.class, actual, "base-value kind 'string'");
                assertEquals(fieldText((RecordValue) payload, "text"), sv.text(), "string text");
            }
            case "number" -> {
                BaseValue.NumberValue nv = assertInstanceOf(BaseValue.NumberValue.class, actual, "base-value kind 'number'");
                assertNumberFormMatches((RecordValue) payload, nv.form());
            }
            default -> fail("unknown expected base-value kind: " + member.name());
        }
    }

    private static void assertNumberFormMatches(RecordValue group, NumberForm actual) {
        RecordValue.Field member = soleField(group, "number-form");
        RecordValue expected = (RecordValue) member.value().value().coreValue();
        String shape = member.name();
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
     * The .tn is a {@code !type-ref token} data-value: at every other layer a bare token is a
     * complete data-value, and here the type-ref is what selects a built-in atom's parsing contract
     * (§5).
     *
     * <p><b>The expected value names the textual family it is written in</b>, rather than being one
     * {@code text} field every atom has to fit -- {@code decimal}, {@code hex}, {@code rational},
     * {@code text}, {@code complex}, {@code duration}. All six are host-representation-neutral, §5.2
     * leaving the concrete bound type implementation-defined, which is the same reasoning the
     * resolver layer's {@code base-value} follows. Which family an atom uses is
     * {@code vocabulary-sidecar.tn}'s to state; this switches on the type-ref only to pick the
     * <em>target</em> type to read into, since {@code ipv6} and the binary family share the
     * {@code hex} spelling but not a host type.
     *
     * <p>On an {@code error} vector the §8.1 category picks the exception, like every other layer --
     * a token the atom's grammar rejects is a resolver error, a parsed value violating its range a
     * validation error. The suite's README flags the first half as not yet settled by the spec.
     */
    private static void checkVocabularyVector(String bucket, Path subject, RecordValue sidecar) throws IOException {
        Document doc = new TsonDataParser(new ByteArrayInputStream(subjectBytes(subject, sidecar))).parseDocument();
        DataValue root = doc.root();
        String typeRef = root.typeRef().orElseThrow(
                () -> new AssertionError("vocabulary vector .tn must carry a type-ref"));
        TokenValue token = assertInstanceOf(TokenValue.class, root.coreValue(),
                "vocabulary vector .tn must be a type-ref'd token");
        AtomType<?> atomType = BuiltinTypeVocabulary.lookup(typeRef)
                .orElseThrow(() -> new AssertionError("unrecognized type-ref in vocabulary vector: " + typeRef));

        assertEquals(fieldText(sidecar, "type-ref"), typeRef,
                "the sidecar's type-ref must name the atom the subject's own type-ref does");
        switch (outcomeOf(sidecar)) {
            case "valid" -> checkValidVocabularyVector(typeRef, atomType, token, outcomePayload(sidecar));
            case "error" -> assertThrows(errorClassFor(sidecar), () -> atomType.read(token));
            default -> fail("unknown vocabulary-layer outcome: " + outcomeOf(sidecar));
        }
    }

    private static void checkValidVocabularyVector(String typeRef, AtomType<?> atomType, TokenValue token, RecordValue valid) {
        RecordValue.Field family = soleField((RecordValue) fieldCore(valid, "value"), "vocabulary value");
        CoreValue payload = family.value().value().coreValue();
        switch (typeRef) {
            case "rational" -> {
                Rational actual = (Rational) atomType.read(token, Rational.class);
                assertEquals(parseRational(((TokenValue) payload).text()), actual, "vocabulary value");
            }
            case "complex" -> {
                Complex actual = (Complex) atomType.read(token, Complex.class);
                RecordValue expected = (RecordValue) payload;
                assertEquals(0, new BigDecimal(fieldText(expected, "real")).compareTo(actual.real()), "complex real part");
                assertEquals(0, new BigDecimal(fieldText(expected, "imaginary")).compareTo(actual.imaginary()), "complex imaginary part");
            }
            case "uuid" -> {
                UUID actual = (UUID) atomType.read(token, UUID.class);
                assertEquals(UUID.fromString(((TokenValue) payload).text()), actual, "vocabulary value");
            }
            case "base64", "base64url", "base32", "hex" -> {
                byte[] actual = (byte[]) atomType.read(token, byte[].class);
                assertArrayEquals(HexFormat.of().parseHex(((TokenValue) payload).text()), actual, "vocabulary value");
            }
            case "date" -> {
                LocalDate actual = (LocalDate) atomType.read(token, LocalDate.class);
                assertEquals(LocalDate.parse(((TokenValue) payload).text()), actual, "vocabulary value");
            }
            case "time" -> {
                OffsetTime actual = (OffsetTime) atomType.read(token, OffsetTime.class);
                assertEquals(OffsetTime.parse(((TokenValue) payload).text()), actual, "vocabulary value");
            }
            case "datetime" -> {
                OffsetDateTime actual = (OffsetDateTime) atomType.read(token, OffsetDateTime.class);
                assertEquals(OffsetDateTime.parse(((TokenValue) payload).text()), actual, "vocabulary value");
            }
            case "duration" -> {
                IsoDuration actual = (IsoDuration) atomType.read(token, IsoDuration.class);
                RecordValue expected = (RecordValue) payload;
                assertEquals(Period.parse(fieldText(expected, "period")), actual.calendarPart(), "duration calendar part");
                assertEquals(Duration.parse(fieldText(expected, "clock")), actual.clockPart(), "duration clock part");
            }
            case "uri" -> {
                URI actual = (URI) atomType.read(token, URI.class);
                assertEquals(URI.create(((TokenValue) payload).text()), actual, "vocabulary value");
            }
            case "ipv4" -> {
                Inet4Address actual = (Inet4Address) atomType.read(token, Inet4Address.class);
                assertEquals(InetAddress.ofLiteral(((TokenValue) payload).text()), actual, "vocabulary value");
            }
            case "ipv6" -> {
                // Unlike ipv4, value is the plain hex string of the 16 raw address bytes (the same
                // convention as the binary family), not a textual IPv6 literal -- InetAddress
                // itself silently collapses an IPv4-mapped 16-byte pattern to an Inet4Address, so
                // there's no single JDK parse this suite could trust as a neutral oracle here.
                Inet6Address actual = (Inet6Address) atomType.read(token, Inet6Address.class);
                assertArrayEquals(HexFormat.of().parseHex(((TokenValue) payload).text()), actual.getAddress(),
                        "vocabulary value");
            }
            case "text", "cidr4", "cidr6", "mac", "email" -> {
                // The atoms whose host value is the authored text itself, so the oracle is a plain string
                // compare with no parse in between. Deliberately not folded into the numeric default arm
                // below. Three reasons converge here: `text` is text by definition (§5.5, "the host value is
                // the token's text"), while `cidr4`/`cidr6`/`mac` keep their text because Java has no type to
                // map onto (see Cidr4Parser/MacParser) and `email` because the address shape is the contract.
                String actual = (String) atomType.read(token, String.class);
                assertEquals(((TokenValue) payload).text(), actual, "vocabulary value");
            }
            default -> {
                BigDecimal actual = (BigDecimal) atomType.read(token, BigDecimal.class);
                BigDecimal expected = new BigDecimal(((TokenValue) payload).text());
                assertEquals(0, expected.compareTo(actual),
                        "vocabulary value: expected " + expected + ", got " + actual);
            }
        }
    }

    private static Rational parseRational(String text) {
        String[] parts = text.split("/", 2);
        return new Rational(new BigInteger(parts[0]), new BigInteger(parts[1]));
    }

    // ── Dynamic meta/import splicing (RUNNER.md's "Schema-governed vectors") ──

    /**
     * Proves {@link Sidecar#splicedSource} both splices the right text, in the right place, and produces a
     * document that genuinely resolves against the real bundled meta.tn/core.tn chain -- not just
     * something that looks plausible. Self-contained: doesn't depend on the sibling test-suite repo
     * being checked out.
     */
    @Test
    void theSpliceInsertsRealDirectivesThatActuallyResolve(@TempDir Path tempDir) throws IOException {
        Path subject = tempDir.resolve("synthetic.tn");
        Files.writeString(subject,
                "!!id:\"https://tson.io/test-suite/schema/valid/synthetic.tn\"\n{ my_int => integer }");
        RecordValue sidecar = (RecordValue) new TsonDataParser("{ meta: \"meta.tn\" import: [\"core.tn\"] }")
                .parseDocument().root().coreValue();

        String resolved = Sidecar.splicedSource(subject, sidecar);

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

    /**
     * The exception an {@code error} vector's §8.1 category demands. Checked at <b>every</b> layer,
     * not only the vocabulary one: asserting merely that something was thrown passes a lexer that
     * rejects a document for the wrong reason, and the category is not derivable from the layer --
     * the vocabulary layer raises {@code resolver} and {@code validation} errors and never a
     * "vocabulary" one.
     */
    private static Class<? extends Throwable> errorClassFor(RecordValue sidecar) {
        String category = fieldText(outcomePayload(sidecar), "category");
        return switch (category) {
            case "lexer" -> LexException.class;
            case "parser" -> TsonParseException.class;
            case "resolver" -> AtomParseException.class;
            case "validation" -> AtomValidationException.class;
            default -> throw new AssertionError("unknown §8.1 category: " + category);
        };
    }

}
