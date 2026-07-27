package io.ltr8.tson.parser.compiler;

import io.ltr8.tson.parser.TsonDataParser;
import io.ltr8.tson.parser.ast.Document;
import io.ltr8.tson.parser.atom.DurationParser;
import io.ltr8.tson.parser.resolver.MetaKernelBootstrapResolver;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.BinaryType;
import io.ltr8.tson.schema.meta.DateTimeType;
import io.ltr8.tson.schema.meta.DateType;
import io.ltr8.tson.schema.meta.DecimalType;
import io.ltr8.tson.schema.meta.DurationType;
import io.ltr8.tson.schema.meta.FloatType;
import io.ltr8.tson.schema.meta.RationalType;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TextType;
import io.ltr8.tson.schema.meta.TimeType;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.Unit;
import io.ltr8.tson.schema.meta.UuidType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Real data text, for each of the atom-family factory constants in {@link AtomTypeParser} -- one
 * small {@code holder} record per family, reusing the exact literal each family's own {@code
 * atom} test already proved valid, read through the real {@link TsonDataParser} and the real
 * compiled parser (not by constructing a {@code TokenValue} directly). Mainly a wiring check --
 * each factory itself does no new parsing/validation, just casts+adapts (see {@link
 * AtomTypeParser}'s own Javadoc) -- but a real wiring mistake (e.g. registering under the wrong
 * constructor name, as {@link BinaryType}'s own {@code "binary"}-not-{@code "binary_type"} naming
 * invites) would only show up here, not in the underlying {@code atom} parser's own
 * tests.
 */
class AtomTypeParserFactoriesTest {

    private static final TsonParserFactoryRegistry REGISTRY = TsonParserFactoryRegistry.builder()
            .register("record", RecordParser.FACTORY)
            .register("text_type", AtomTypeParser.TEXT_TYPE)
            .register("decimal_type", AtomTypeParser.DECIMAL_TYPE)
            .register("float_type", AtomTypeParser.FLOAT_TYPE)
            .register("rational_type", AtomTypeParser.RATIONAL_TYPE)
            .register("uuid_type", AtomTypeParser.UUID_TYPE)
            .register("binary", AtomTypeParser.BINARY)
            .register("date_type", AtomTypeParser.DATE_TYPE)
            .register("time_type", AtomTypeParser.TIME_TYPE)
            .register("datetime_type", AtomTypeParser.DATETIME_TYPE)
            .register("duration_type", AtomTypeParser.DURATION_TYPE)
            .register("unit", AtomTypeParser.UNIT)
            .build();

    private static Object readValue(Top atomBody, String source) {
        TypeDefinition atomEntry = new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), false,
                List.of(), List.of(), Optional.empty(), atomBody);
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("field", atomEntry);
        entries.put("holder", TypeDefinition.product(
                RecordBody.of(List.of(RecordField.required("value", TypeRef.of("field"))))));
        TsonSchema schema = new TsonSchema("https://example.test/s.tn1",
                "https://example.test/meta.tn1", List.of(), entries);
        TsonLinkedSchema linkedSchema = new TsonLinkedSchema(schema);
        TsonCompiledSchema compiled = TsonSchemaCompiler.compile(linkedSchema, REGISTRY);

        Document document = new TsonDataParser(source).parseDocument();
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) compiled.get("holder").read(document.root());
        return result.get("value");
    }

    @Test
    void text() {
        assertEquals("hello", readValue(TextType.UNCONSTRAINED, "{ value: hello }"));
    }

    @Test
    void decimal() {
        assertEquals(new java.math.BigDecimal("199.90"), readValue(DecimalType.UNCONSTRAINED, "{ value: 199.90 }"));
    }

    @Test
    void floatType() {
        assertEquals(199.90, (double) readValue(FloatType.FLOAT64, "{ value: 199.90 }"), 0.0001);
    }

    @Test
    void rational() {
        // "/" isn't a valid bare-token character in TSON's own grammar -- real data quotes it.
        io.ltr8.tson.schema.meta.Rational expected = new io.ltr8.tson.schema.meta.Rational(
                java.math.BigInteger.valueOf(2), java.math.BigInteger.valueOf(3));
        assertEquals(expected, readValue(RationalType.UNCONSTRAINED, "{ value: \"2/3\" }"));
    }

    @Test
    void uuid() {
        assertEquals(java.util.UUID.fromString("9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09"),
                readValue(UuidType.UNCONSTRAINED, "{ value: 9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09 }"));
    }

    @Test
    void binary() {
        byte[] expected = "Man".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(java.util.Arrays.toString(expected),
                java.util.Arrays.toString((byte[]) readValue(BinaryType.BASE64, "{ value: TWFu }")));
    }

    @Test
    void date() {
        assertEquals(java.time.LocalDate.of(2025, 3, 13), readValue(DateType.UNCONSTRAINED, "{ value: 2025-03-13 }"));
    }

    @Test
    void time() {
        // ":" is a structural character (field syntax) -- not valid inside a bare token either.
        assertEquals(java.time.OffsetTime.parse("10:15:30Z"), readValue(TimeType.UNCONSTRAINED, "{ value: \"10:15:30Z\" }"));
    }

    @Test
    void dateTime() {
        assertEquals(java.time.OffsetDateTime.parse("2025-03-13T10:15:30Z"),
                readValue(DateTimeType.UNCONSTRAINED, "{ value: \"2025-03-13T10:15:30Z\" }"));
    }

    @Test
    void duration() {
        assertEquals("PT1H30M", DurationParser.UNCONSTRAINED.write(
                (io.ltr8.tson.schema.meta.IsoDuration) readValue(DurationType.UNCONSTRAINED, "{ value: PT1H30M }")));
    }

    @Test
    void unit() {
        assertEquals("anything", readValue(new Unit(), "{ value: anything }"));
    }

    /**
     * {@code uri_type}/{@code regex_type} are the one known exception discussed in {@link
     * AtomTypeParser}'s own Javadoc -- their RFC citation is a *schema-composed* default that
     * generic binding can't fill in during schema *resolution*, so {@code MetaKernelBootstrapResolver} hand-
     * picks their binding instead. That gap is upstream of this layer entirely: by the time the
     * real {@code uri}/{@code regex} entries reach here, their constraints are already correctly
     * filled in, so reading real *data* against them works exactly like every other family --
     * confirmed here against the real resolved entries, not a hand-built stand-in.
     */
    @Test
    void uriUsesTheRealMetaKernelResolvedEntryIncludingItsSchemaComposedRfcCitation() {
        TsonSchema metaKernel = MetaKernelBootstrapResolver.getMetaKernelSchema();
        Map<String, Object> result = readAgainstRealEntry("uri", metaKernel.entries().get("uri"),
                "uri_type", AtomTypeParser.URI_TYPE, "{ value: \"https://example.com/a/b?x=1#frag\" }");

        assertEquals(java.net.URI.create("https://example.com/a/b?x=1#frag"), result.get("value"));
    }

    @Test
    void regexUsesTheRealMetaKernelResolvedEntryIncludingItsSchemaComposedRfcCitation() {
        TsonSchema metaKernel = MetaKernelBootstrapResolver.getMetaKernelSchema();
        Map<String, Object> result = readAgainstRealEntry("regex", metaKernel.entries().get("regex"),
                "regex_type", AtomTypeParser.REGEX_TYPE, "{ value: \"[a-z]+\" }");

        assertEquals("[a-z]+", result.get("value"));
    }

    private static Map<String, Object> readAgainstRealEntry(String typeName, TypeDefinition realEntry,
                                                              String constructorKey, TsonParserFactory factory,
                                                              String source) {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put(typeName, realEntry);
        entries.put("holder", TypeDefinition.product(
                RecordBody.of(List.of(RecordField.required("value", TypeRef.of(typeName))))));
        TsonSchema schema = new TsonSchema("https://example.test/s.tn1",
                "https://example.test/meta.tn1", List.of(), entries);
        TsonLinkedSchema linkedSchema = new TsonLinkedSchema(schema);
        TsonParserFactoryRegistry registry = TsonParserFactoryRegistry.builder()
                .register("record", RecordParser.FACTORY)
                .register(constructorKey, factory)
                .build();
        TsonCompiledSchema compiled = TsonSchemaCompiler.compile(linkedSchema, registry);

        Document document = new TsonDataParser(source).parseDocument();
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) compiled.get("holder").read(document.root());
        return result;
    }
}
