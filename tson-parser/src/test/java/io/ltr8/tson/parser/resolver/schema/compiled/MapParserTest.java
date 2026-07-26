package io.ltr8.tson.parser.resolver.schema.compiled;

import io.ltr8.tson.parser.Parser;
import io.ltr8.tson.parser.ast.Document;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.IntegerType;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;
import io.ltr8.tson.schema.meta.TypeRef;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end proof of {@link MapParser} against real TSON data source text. */
class MapParserTest {

    private static TypeDefinition integerEntry() {
        return new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), false, List.of(), List.of(),
                Optional.empty(), IntegerType.UNCONSTRAINED);
    }

    private static TsonParserFactoryRegistry registry() {
        return TsonParserFactoryRegistry.builder()
                .register("integer_type", AtomTypeParser.INTEGER_TYPE)
                .register("map", MapParser.FACTORY)
                .build();
    }

    private static TsonCompiledSchema compile(MapBody body) {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("integer", integerEntry());
        entries.put("scores", TypeDefinition.product(body));
        TsonSchema schema = new TsonSchema("https://example.test/s.tn1",
                "https://example.test/meta.tn1", List.of(), entries);
        return TsonSchemaCompiler.compile(schema, registry());
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> readMap(TsonCompiledSchema compiled, String source) {
        Document document = new Parser(source).parseDocument();
        return (Map<Object, Object>) compiled.get("scores").read(document.root());
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

    @Test
    void absentSentinelAsKeyThrows() {
        TsonCompiledSchema compiled = compile(MapBody.of(TypeRef.of("integer"), TypeRef.of("integer")));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> readMap(compiled, "{ _ => 1 }"));
        assertTrue(thrown.getMessage().contains("absent sentinel"), thrown.getMessage());
    }

    @Test
    void minItemsRejectsTooFewEntries() {
        MapBody body = new MapBody(TypeRef.of("integer"), TypeRef.of("integer"), Optional.of(BigInteger.TWO), Optional.empty());
        TsonCompiledSchema compiled = compile(body);

        assertEquals(2, readMap(compiled, "{ 1 => 1 2 => 2 }").size());
        assertThrows(IllegalArgumentException.class, () -> readMap(compiled, "{ 1 => 1 }"));
    }

    @Test
    void maxItemsRejectsTooManyEntries() {
        MapBody body = new MapBody(TypeRef.of("integer"), TypeRef.of("integer"), Optional.empty(), Optional.of(BigInteger.ONE));
        TsonCompiledSchema compiled = compile(body);

        assertEquals(1, readMap(compiled, "{ 1 => 1 }").size());
        assertThrows(IllegalArgumentException.class, () -> readMap(compiled, "{ 1 => 1 2 => 2 }"));
    }
}
