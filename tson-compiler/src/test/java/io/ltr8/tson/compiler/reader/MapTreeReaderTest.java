package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TestDocuments;
import io.ltr8.tson.compiler.TsonCompiledSchema;
import io.ltr8.tson.compiler.TsonDiagnosticsCollector;
import io.ltr8.tson.compiler.TsonReadException;
import io.ltr8.tson.compiler.TsonSchemaCompiler;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.IntegerType;
import io.ltr8.tson.schema.meta.ElementState;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.tree.TsonAbsent;
import io.ltr8.tson.tree.TsonAtom;
import io.ltr8.tson.tree.TsonMap;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end proof of {@link MapTreeReader} against real TSON data source text. */
class MapTreeReaderTest {

    private static TypeDefinition integerEntry() {
        return new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), false, List.of(), List.of(),
                Optional.empty(), IntegerType.UNCONSTRAINED);
    }

    private static TsonCompiledSchema compile(MapBody body) {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("integer", integerEntry());
        entries.put("scores", TypeDefinition.product(body));
        TsonSchema schema = new TsonSchema("https://example.test/s.tn",
                "https://example.test/meta.tn", List.of(), entries);
        TsonLinkedSchema linkedSchema = new TsonLinkedSchema(schema);
        return TsonSchemaCompiler.compile(linkedSchema, ValueReaderFactoryRegistry.tree());
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> readMap(TsonCompiledSchema compiled, String source) {
        return (Map<Object, Object>) Dom.of((TsonValue) compiled.get("scores")
                .read(TestDocuments.document(source)));
    }

    @Test
    void readsAPlainMapOfIntegerToInteger() {
        TsonCompiledSchema compiled = compile(MapBody.of(TypeRef.of("integer"), TypeRef.of("integer")));

        Map<Object, Object> result = readMap(compiled, "{ 1 => 10 2 => 20 }");
        assertEquals(BigInteger.TEN, result.get(BigInteger.ONE));
        assertEquals(BigInteger.valueOf(20), result.get(BigInteger.TWO));
    }

    @Test
    void emptyBraceReadsAsEmptyMap() {
        TsonCompiledSchema compiled = compile(MapBody.of(TypeRef.of("integer"), TypeRef.of("integer")));

        assertEquals(Map.of(), readMap(compiled, "{}"));
    }

    /**
     * [TSON-DATA] §2.8 resolves {@code {}} to "the empty container of that type", so at a map position it is
     * a map holding nothing -- which is exactly what {@code min_items: 1} forbids. It used to be accepted
     * silently: only the entry loop validated the count, and {@code {}} never enters the entry loop.
     */
    @Test
    void emptyBraceIsAZeroEntryMapForMinItemsToo() {
        MapBody body = new MapBody(TypeRef.of("integer"), TypeRef.of("integer"), ElementState.REQUIRED, Optional.of(BigInteger.ONE),
                Optional.empty());
        TsonCompiledSchema compiled = compile(body);
        TsonDiagnosticsCollector problems = new TsonDiagnosticsCollector();

        compiled.get("scores").read(TestDocuments.document("{}", problems));

        assertEquals(1, problems.diagnostics().size(), problems.diagnostics().toString());
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, problems.diagnostics().get(0).code());
        assertTrue(problems.diagnostics().get(0).message().contains("has 0 entries, fewer than the minimum 1"),
                problems.diagnostics().get(0).message());
    }

    /** The same count against an upper bound: zero entries satisfy any {@code max_items}, and still do. */
    @Test
    void emptyBraceSatisfiesMaxItems() {
        MapBody body = new MapBody(TypeRef.of("integer"), TypeRef.of("integer"), ElementState.REQUIRED, Optional.empty(),
                Optional.of(BigInteger.ONE));

        assertEquals(Map.of(), readMap(compile(body), "{}"));
    }

    @Test
    void absentSentinelAsKeyThrows() {
        TsonCompiledSchema compiled = compile(MapBody.of(TypeRef.of("integer"), TypeRef.of("integer")));

        TsonReadException thrown = assertThrows(TsonReadException.class,
                () -> readMap(compiled, "{ _ => 1 }"));
        assertTrue(thrown.getMessage().contains("absent sentinel"), thrown.getMessage());
    }

    /** A map whose values may be absent -- {@code {K => V?}}, the sugar for {@code state: OPTIONAL}. */
    private static MapBody optionalValues() {
        return new MapBody(TypeRef.of("integer"), TypeRef.of("integer"), ElementState.OPTIONAL, Optional.empty(),
                Optional.empty());
    }

    /**
     * Under {@code state: OPTIONAL} an entry's value may be the absent sentinel: the entry is present with an
     * absent value ([TSON-DATA] §2.9), so the key is decoded and kept and nothing is reported. This is
     * [TSON-SCHEMA] §7.6's permission, now conditional on the declaration the way an array element's is.
     */
    @Test
    void anAbsentEntryValueIsPermittedUnderOptional() {
        TsonCompiledSchema compiled = compile(optionalValues());
        TsonDiagnosticsCollector problems = new TsonDiagnosticsCollector();

        TsonMap result = (TsonMap) compiled.get("scores").read(TestDocuments.document("{ 1 => _  2 => 20 }", problems));

        assertEquals(List.of(), problems.diagnostics(), problems.diagnostics().toString());
        assertEquals(2, result.entries().size());
        assertEquals(TsonAbsent.instance(), result.entries().get(0).value());
        assertEquals(BigInteger.ONE, ((TsonAtom) result.entries().get(0).key()).value());
    }

    /**
     * And under the default REQUIRED it is refused, which is the whole point of giving {@code map} a {@code
     * state} field: {@code {K => V}} now means what {@code [T]} means, and an author who wants absence says
     * so. Reported as {@code FIELD_REQUIRED} at the entry's own path, the answer {@code
     * ArrayAbstractReader} gives a required element.
     */
    @Test
    void anAbsentEntryValueIsRefusedUnderTheDefaultRequired() {
        TsonCompiledSchema compiled = compile(MapBody.of(TypeRef.of("integer"), TypeRef.of("integer")));
        TsonDiagnosticsCollector problems = new TsonDiagnosticsCollector();

        compiled.get("scores").read(TestDocuments.document("{ 1 => _ }", problems));

        assertEquals(1, problems.diagnostics().size(), problems.diagnostics().toString());
        Diagnostic reported = problems.diagnostics().getFirst();
        assertEquals(Diagnostic.Code.FIELD_REQUIRED, reported.code());
        assertEquals(Optional.of("/1"), reported.path());
        assertTrue(reported.message().contains("is absent, but values are required"), reported.message());
    }

    /**
     * An entry whose value is absent is still an entry: [TSON-DATA] §2.9 has higher parts that impose size
     * constraints "count all slots", so two absent-valued entries satisfy {@code min_items: 2} and breach
     * {@code max_items: 1}. Counted the same under either state -- the refusal above costs the value its
     * verdict, not the entry its place.
     */
    @Test
    void anAbsentEntryValueCountsTowardTheSizeBounds() {
        MapBody atLeastTwo = new MapBody(TypeRef.of("integer"), TypeRef.of("integer"), ElementState.OPTIONAL,
                Optional.of(BigInteger.TWO), Optional.empty());
        assertEquals(2, ((TsonMap) compile(atLeastTwo).get("scores")
                .read(TestDocuments.document("{ 1 => _  2 => _ }"))).entries().size());

        MapBody atMostOne = new MapBody(TypeRef.of("integer"), TypeRef.of("integer"), ElementState.OPTIONAL,
                Optional.empty(), Optional.of(BigInteger.ONE));
        TsonReadException thrown = assertThrows(TsonReadException.class,
                () -> compile(atMostOne).get("scores").read(TestDocuments.document("{ 1 => _  2 => _ }")));
        assertTrue(thrown.getMessage().contains("has 2 entries, more than the maximum 1"), thrown.getMessage());
    }

    /** The permission is the value position's alone -- §2.9's own rule still refuses the sentinel as a key. */
    @Test
    void anAbsentKeyIsStillRefusedWhenTheValueIsAbsentToo() {
        TsonCompiledSchema compiled = compile(MapBody.of(TypeRef.of("integer"), TypeRef.of("integer")));

        TsonReadException thrown = assertThrows(TsonReadException.class,
                () -> readMap(compiled, "{ _ => _ }"));
        assertTrue(thrown.getMessage().contains("absent sentinel"), thrown.getMessage());
    }

    /**
     * [TSON-DATA] §2.6 makes a repeated key MUST NOT; this reports it and applies the last-value-wins
     * recovery underneath anyway.
     */
    @Test
    void duplicateKeyIsAValidationError() {
        TsonCompiledSchema compiled = compile(MapBody.of(TypeRef.of("integer"), TypeRef.of("integer")));

        TsonReadException thrown = assertThrows(TsonReadException.class,
                () -> readMap(compiled, "{ 1 => 10  1 => 20 }"));
        assertTrue(thrown.getMessage().contains("duplicate key '1'"), thrown.getMessage());
    }

    /**
     * Keys compare by their <em>decoded</em> value, so two spellings of one integer are one key -- which is
     * the case the sink's own {@code put} would otherwise have collapsed with nothing to see. The recovery
     * still runs: the map comes back with the one key and the later value.
     */
    @Test
    void duplicateKeyIsJudgedOnTheDecodedValueNotTheWrittenText() {
        TsonCompiledSchema compiled = compile(MapBody.of(TypeRef.of("integer"), TypeRef.of("integer")));
        TsonDiagnosticsCollector problems = new TsonDiagnosticsCollector();

        @SuppressWarnings("unchecked")
        Map<Object, Object> result = (Map<Object, Object>) Dom.of((TsonValue) compiled.get("scores")
                .read(TestDocuments.document("{ 0xFF => 10  255 => 20 }", problems)));

        assertEquals(List.of(Diagnostic.Code.DUPLICATE_MAP_KEY),
                problems.diagnostics().stream().map(Diagnostic::code).toList(),
                problems.diagnostics().toString());
        assertEquals(Map.of(BigInteger.valueOf(255), BigInteger.valueOf(20)), result);
    }

    /**
     * A key that failed to decode is not a key the document stated, so it never joins the seen set -- two
     * equally-undecodable keys are two atom violations, not an atom violation plus a phantom duplicate.
     */
    @Test
    void anUndecodableKeyIsNotCountedAsSeen() {
        TsonCompiledSchema compiled = compile(MapBody.of(TypeRef.of("integer"), TypeRef.of("integer")));
        TsonDiagnosticsCollector problems = new TsonDiagnosticsCollector();

        compiled.get("scores").read(TestDocuments.document("{ \"a\" => 1  \"b\" => 2 }", problems));

        assertEquals(List.of(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION),
                problems.diagnostics().stream().map(Diagnostic::code).toList(),
                problems.diagnostics().toString());
    }

    @Test
    void minItemsRejectsTooFewEntries() {
        MapBody body = new MapBody(TypeRef.of("integer"), TypeRef.of("integer"), ElementState.REQUIRED, Optional.of(BigInteger.TWO), Optional.empty());
        TsonCompiledSchema compiled = compile(body);

        assertEquals(2, readMap(compiled, "{ 1 => 1 2 => 2 }").size());
        assertThrows(TsonReadException.class, () -> readMap(compiled, "{ 1 => 1 }"));
    }

    @Test
    void maxItemsRejectsTooManyEntries() {
        MapBody body = new MapBody(TypeRef.of("integer"), TypeRef.of("integer"), ElementState.REQUIRED, Optional.empty(), Optional.of(BigInteger.ONE));
        TsonCompiledSchema compiled = compile(body);

        assertEquals(1, readMap(compiled, "{ 1 => 1 }").size());
        assertThrows(TsonReadException.class, () -> readMap(compiled, "{ 1 => 1 2 => 2 }"));
    }
}
