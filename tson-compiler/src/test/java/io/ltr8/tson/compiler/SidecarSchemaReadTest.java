package io.ltr8.tson.compiler;

import io.ltr8.tson.suite.SuiteCheckout;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonCanonicalIdentity;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads real sidecar documents against the suite's own sidecar schemas, which is the check
 * {@link SidecarSchemasTest} cannot make: resolving proves a schema is well-formed, not that it
 * accepts what the corpus writes or refuses what it must.
 *
 * <p><b>What the field groups (§5.11) buy.</b> Each layer's sidecar is one REQUIRED group over its
 * outcomes, and a core-value is one REQUIRED group over its six kinds. That makes the two
 * correlations a flat discriminator could state but not enforce into schema-level facts: an outcome
 * cannot appear without its payload, a payload cannot appear without its outcome, and a token
 * core-value cannot carry a record's fields. The negative cases below are the point of the design,
 * so they are asserted rather than assumed.
 */
class SidecarSchemaReadTest {

    private static final String SUITE_SCHEMA_PREFIX = "https://tson.io/test-suite/schemas/";

    /** One compiled reader per layer, built once: compiling the chain per sidecar would dominate the run. */
    private static final Map<String, TsonTypeReader<?>> READERS = new HashMap<>();

    private static synchronized TsonTypeReader<?> reader(String layer) {
        return READERS.computeIfAbsent(layer, SidecarSchemaReadTest::compile);
    }

    private static TsonTypeReader<?> compile(String layer) {
        Path schemas = SuiteCheckout.schemasRoot().orElseThrow();
        TsonSchemaSource source = uri -> {
            String canonical = TsonCanonicalIdentity.canonicalize(uri);
            String prefix = TsonCanonicalIdentity.canonicalize(SUITE_SCHEMA_PREFIX);
            if (!canonical.startsWith(prefix)) {
                throw new IllegalStateException("unexpected fetch: " + uri);
            }
            return read(schemas.resolve(canonical.substring(prefix.length())));
        };
        TsonCompiledMetaRegistry core =
                TsonCompiledMetaRegistry.withStandardLibrary(SchemaMetaNameBinder.defaultContext(), source);
        return TsonCompiledSchemaRegistry.tree(core)
                .get(SUITE_SCHEMA_PREFIX + layer + "-sidecar.tn")
                .get(layer + "_sidecar");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void accepts(String layer, String body) {
        reader(layer).read(TestDocuments.document(body));
    }

    private static String refuses(String layer, String body) {
        return assertThrows(TsonReadException.class, () -> accepts(layer, body)).getMessage();
    }

    /**
     * Every sidecar in the corpus, read against the schema it names. This is what makes
     * {@code schemas/} live rather than documentation: before the sidecars carried {@code !!schema}
     * nothing checked that a fixture matched its own stated shape, and the shape could not have
     * caught much if it had -- an outcome without its payload, or a token core-value carrying a
     * record's fields, both validated under the flat form these replaced.
     */
    @TestFactory
    Stream<DynamicTest> everySidecarConformsToItsLayerSchema() {
        SuiteCheckout.assumeAvailable();
        Path tests = SuiteCheckout.testsRoot().orElseThrow();
        try (Stream<Path> walk = Files.walk(tests)) {
            return walk.filter(p -> p.getFileName().toString().endsWith("-expected.tn"))
                    .sorted()
                    .map(p -> DynamicTest.dynamicTest(tests.relativize(p).toString(),
                            () -> reader(layerOf(tests, p)).read(TestDocuments.document(read(p)))))
                    .toList()
                    .stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code tests/<class>/<layer>/<bucket>/<slug>-expected.tn} -- the layer names the schema. */
    private static String layerOf(Path tests, Path sidecar) {
        return tests.relativize(sidecar).getName(1).toString();
    }

    // ── The outcome group ────────────────────────────────────────────────

    @Test
    void aLexerErrorVectorCarriesItsCategoryAndNoTokens() {
        SuiteCheckout.assumeAvailable();
        accepts("lexer", """
                {
                  spec: "§7.2.4"
                  description: "A bare '+' has no grammar role"
                  error: { category: lexer }
                }""");
    }

    @Test
    void aLexerValidVectorCarriesItsTokenStream() {
        SuiteCheckout.assumeAvailable();
        accepts("lexer", """
                {
                  spec: "§7.3"
                  description: "One unquoted token"
                  valid: { tokens: [ { kind: unquoted-token  text: "42" } ] }
                }""");
    }

    @Test
    void aVectorStatingBothOutcomesIsRefused() {
        SuiteCheckout.assumeAvailable();
        String message = refuses("lexer", """
                {
                  spec: "§7.3"
                  description: "Cannot be both"
                  valid: { tokens: [] }
                  error: { category: lexer }
                }""");
        assertTrue(message.contains("at most one"), message);
    }

    @Test
    void aVectorStatingNoOutcomeIsRefused() {
        SuiteCheckout.assumeAvailable();
        String message = refuses("lexer", """
                {
                  spec: "§7.3"
                  description: "States nothing"
                }""");
        assertTrue(message.contains("exactly one"), message);
    }

    /** The correlation the flat shape could not enforce: `valid` without the tokens it exists to carry. */
    @Test
    void aValidOutcomeWithoutItsPayloadIsRefused() {
        SuiteCheckout.assumeAvailable();
        String message = refuses("lexer", """
                {
                  spec: "§7.3"
                  description: "Valid, but says nothing about what was lexed"
                  valid: { }
                }""");
        assertTrue(message.contains("tokens"), message);
    }

    // ── The core-value group ─────────────────────────────────────────────

    @Test
    void aParserVectorReadsAWholeDocumentTree() {
        SuiteCheckout.assumeAvailable();
        accepts("parser", """
                {
                  spec: "§2.5"
                  description: "A record with one field"
                  valid: {
                    document: {
                      id: _
                      schema: _
                      root: {
                        annotations: []
                        type-ref: _
                        core: { record: { fields: [
                          { name: "a"
                            value: { schema-ref: _  value: {
                              annotations: []  type-ref: _
                              core: { token: { form: unquoted  text: "1" } } } } }
                        ] } }
                      }
                    }
                  }
                }""");
    }

    /** `absent` and `empty-brace` have no payload, so they are typed void and written `_`. */
    @Test
    void aPayloadlessCoreValueIsWrittenAsTheAbsentSentinel() {
        SuiteCheckout.assumeAvailable();
        accepts("parser", """
                {
                  spec: "§2.9"
                  description: "The absent sentinel as a root value"
                  valid: {
                    document: {
                      id: _  schema: _
                      root: { annotations: []  type-ref: _  core: { absent: _ } }
                    }
                  }
                }""");
    }

    /** A token cannot carry a record's fields -- the whole reason the kind discriminator became a group. */
    @Test
    void aCoreValueMixingTwoKindsIsRefused() {
        SuiteCheckout.assumeAvailable();
        String message = refuses("parser", """
                {
                  spec: "§2.3"
                  description: "Both a token and a record"
                  valid: {
                    document: {
                      id: _  schema: _
                      root: { annotations: []  type-ref: _  core: {
                        token: { form: unquoted  text: "1" }
                        record: { fields: [] } } }
                    }
                  }
                }""");
        assertTrue(message.contains("at most one"), message);
    }

    @Test
    void aSchemaDocumentOutcomeCarriesNothingFurther() {
        SuiteCheckout.assumeAvailable();
        accepts("parser", """
                {
                  spec: "§1.5"
                  description: "A header carrying !!meta is a schema document"
                  schema-document: _
                }""");
    }

    // ── The remaining two layers ─────────────────────────────────────────

    @Test
    void aResolverVectorCarriesTheNumberFormItsShapeActuallyHas() {
        SuiteCheckout.assumeAvailable();
        accepts("resolver", """
                {
                  spec: "§4.3"
                  description: "A hex-based integer"
                  valid: { base-value: { number: { based-integer: {
                    sign: _  radix: hex  digits: "FF" } } } }
                }""");
    }

    /** A based-integer needs its radix; a float has none to give. The flat number_form allowed either mistake. */
    @Test
    void aBasedIntegerWithoutARadixIsRefused() {
        SuiteCheckout.assumeAvailable();
        String message = refuses("resolver", """
                {
                  spec: "§4.3"
                  description: "Based, but says on what"
                  valid: { base-value: { number: { based-integer: { sign: _  digits: "FF" } } } }
                }""");
        assertTrue(message.contains("radix"), message);
    }

    @Test
    void aVocabularyVectorNamesTheFormItsValueIsWrittenIn() {
        SuiteCheckout.assumeAvailable();
        accepts("vocabulary", """
                {
                  spec: "§5.6"
                  description: "An int32"
                  type-ref: "int32"
                  valid: { value: { decimal: "42" } }
                }""");
    }

    /** The two families the flat `value: text?` could not express at all, though sidecars always wrote them. */
    @Test
    void aVocabularyValueMayBeANestedComplexRecordOrAScalarCount() {
        SuiteCheckout.assumeAvailable();
        accepts("vocabulary", """
                {
                  spec: "§5.6"
                  description: "A complex number"
                  type-ref: "complex"
                  valid: { value: { complex: { real: "1"  imaginary: "-2" } } }
                }""");
        // duration and period state the value -- seconds and months -- rather than a spelling, so both
        // ride `decimal` and neither needs a member of its own.
        accepts("vocabulary", """
                {
                  spec: "§5.5"
                  description: "A duration, as seconds"
                  type-ref: "duration"
                  valid: { value: { decimal: "7200" } }
                }""");
        accepts("vocabulary", """
                {
                  spec: "§5.5"
                  description: "A period, as months"
                  type-ref: "period"
                  valid: { value: { decimal: "12" } }
                }""");
    }

    /** type-ref sits outside the outcome group because an error vector needs it too. */
    @Test
    void aVocabularyErrorVectorStillNamesItsAtom() {
        SuiteCheckout.assumeAvailable();
        accepts("vocabulary", """
                {
                  spec: "§5.6"
                  description: "Out of range for int32"
                  type-ref: "int32"
                  error: { category: validation }
                }""");
        String message = refuses("vocabulary", """
                {
                  spec: "§5.6"
                  description: "Names no atom"
                  error: { category: validation }
                }""");
        assertTrue(message.contains("type-ref"), message);
    }
}
