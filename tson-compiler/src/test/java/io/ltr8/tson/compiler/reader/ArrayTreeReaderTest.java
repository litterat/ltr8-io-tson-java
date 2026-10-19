package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.ReadException;
import io.ltr8.tson.compiler.TestDocuments;
import io.ltr8.tson.base.DiagnosticsCollector;
import io.ltr8.tson.compiler.TsonCompiledSchema;
import io.ltr8.tson.compiler.TsonSchemaCompiler;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.IntegerType;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeKind;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof of {@link ArrayTreeReader} against real TSON data source text -- both standalone
 * (a top-level {@code numbers: array} entry) and nested inside a {@link RecordTreeReader}-built
 * record (an {@code items: [integer]}-shaped field), the realistic case: almost every real array in
 * a materialized schema is reached this way, not as a schema's own top-level entry.
 */
class ArrayTreeReaderTest {

    private static Top integerEntry() {
        return IntegerType.UNCONSTRAINED;
    }

    private static TsonCompiledSchema compile(Map<String, Top> extraEntries) {
        Map<String, Top> entries = new LinkedHashMap<>();
        entries.put("integer", integerEntry());
        entries.putAll(extraEntries);
        TsonSchema schema = new TsonSchema("https://example.test/s.tn",
                "https://example.test/meta.tn", List.of(), entries);
        TsonLinkedSchema linkedSchema = new TsonLinkedSchema(schema);
        return TsonSchemaCompiler.compile(linkedSchema, ValueReaderFactoryRegistry.tree());
    }

    @SuppressWarnings("unchecked")
    private static List<Object> readArray(TsonCompiledSchema compiled, String rootName, String source) {
        return (List<Object>) Dom.of((TsonValue) compiled.get(rootName)
                .read(TestDocuments.document(source)));
    }

    @Test
    void readsAPlainArrayOfIntegers() {
        TsonCompiledSchema compiled = compile(Map.of("numbers", ArrayBody.of(TypeRef.of("integer"))));

        assertEquals(List.of(BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(3)),
                readArray(compiled, "numbers", "[1 2 3]"));
    }

    @Test
    void emptyArrayReadsAsEmptyList() {
        TsonCompiledSchema compiled = compile(Map.of("numbers", ArrayBody.of(TypeRef.of("integer"))));

        assertEquals(List.of(), readArray(compiled, "numbers", "[]"));
    }

    @Test
    void minItemsRejectsAShorterArray() {
        ArrayBody body = new ArrayBody(TypeRef.of("integer"), false, true, false,
                Optional.of(BigInteger.TWO), Optional.empty());
        TsonCompiledSchema compiled = compile(Map.of("numbers", body));

        assertEquals(List.of(BigInteger.ONE, BigInteger.TWO), readArray(compiled, "numbers", "[1 2]"));
        assertThrows(ReadException.class, () -> readArray(compiled, "numbers", "[1]"));
    }

    @Test
    void maxItemsRejectsALongerArray() {
        ArrayBody body = new ArrayBody(TypeRef.of("integer"), false, true, false,
                Optional.empty(), Optional.of(BigInteger.TWO));
        TsonCompiledSchema compiled = compile(Map.of("numbers", body));

        assertEquals(List.of(BigInteger.ONE, BigInteger.TWO), readArray(compiled, "numbers", "[1 2]"));
        assertThrows(ReadException.class, () -> readArray(compiled, "numbers", "[1 2 3]"));
    }

    @Test
    void uniqueItemsRejectsADuplicateDecodedElement() {
        ArrayBody body = new ArrayBody(TypeRef.of("integer"), false, true, true,
                Optional.empty(), Optional.empty());
        TsonCompiledSchema compiled = compile(Map.of("numbers", body));

        assertEquals(List.of(BigInteger.ONE, BigInteger.TWO), readArray(compiled, "numbers", "[1 2]"));
        assertThrows(ReadException.class, () -> readArray(compiled, "numbers", "[1 2 1]"));
    }

    /**
     * The duplicate is named as the value it is. In tree mode the decoded element is a {@code TsonAtom}, so
     * this is the site where the record's default rendering would reach both the message and {@code actual}
     * -- the field a consumer reads instead of parsing the message.
     */
    @Test
    void aDuplicateElementIsReportedAsItsValueNotAsATreeNodesComponents() {
        ArrayBody body = new ArrayBody(TypeRef.of("integer"), false, true, true,
                Optional.empty(), Optional.empty());
        TsonCompiledSchema compiled = compile(Map.of("numbers", body));
        DiagnosticsCollector problems = DiagnosticsReceiver.collecting();

        compiled.get("numbers").read(TestDocuments.document("[1 2 1]", problems));

        Diagnostic reported = problems.diagnostics().getFirst();
        assertEquals("1", reported.actual());
        assertTrue(reported.message().contains("'1' appears more than once"), reported.message());
    }

    @Test
    void aVoidableElementToleratesTheVoidSentinel() {
        ArrayBody body = new ArrayBody(TypeRef.of("integer"), true, true, false,
                Optional.empty(), Optional.empty());
        TsonCompiledSchema compiled = compile(Map.of("numbers", body));

        List<Object> result = readArray(compiled, "numbers", "[1 _ 3]");
        assertEquals(BigInteger.ONE, result.get(0));
        assertNull(result.get(1));
        assertEquals(BigInteger.valueOf(3), result.get(2));
    }

    @Test
    void aNonVoidableElementRejectsTheVoidSentinel() {
        ArrayBody body = new ArrayBody(TypeRef.of("integer"), false, true, false,
                Optional.empty(), Optional.empty());
        TsonCompiledSchema compiled = compile(Map.of("numbers", body));

        assertThrows(ReadException.class, () -> readArray(compiled, "numbers", "[1 _ 3]"));
    }

    @Test
    void arrayFieldNestedInsideARecordReadsCorrectly() {
        Map<String, Top> extra = new LinkedHashMap<>();
        extra.put("numbers", ArrayBody.of(TypeRef.of("integer")));
        extra.put("holder", RecordBody.of(RecordBody.fieldMap(Map.entry("items",
                RecordField.required(TypeRef.of("numbers"))))));
        TsonCompiledSchema compiled = compile(extra);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) Dom.of((TsonValue) compiled.get("holder")
                .read(TestDocuments.document("{ items: [1 2 3] }")));

        assertEquals(List.of(BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(3)), result.get("items"));
    }
}
