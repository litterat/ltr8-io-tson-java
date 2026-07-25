package io.ltr8.tson.parser.resolver.schema.compiled;

import io.ltr8.tson.parser.Parser;
import io.ltr8.tson.parser.ast.Document;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.ElementState;
import io.ltr8.tson.schema.meta.IntegerType;
import io.ltr8.tson.schema.meta.TextType;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.schema.meta.TupleElement;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** End-to-end proof of {@link TupleParser} against real TSON data source text -- a heterogeneous (integer, text) pair. */
class TupleParserTest {

    private static ParserFactoryRegistry registry() {
        return ParserFactoryRegistry.builder()
                .register("integer_type", AtomTypeParser.INTEGER_TYPE)
                .register("text_type", AtomTypeParser.TEXT_TYPE)
                .register("tuple", TupleParser.FACTORY)
                .build();
    }

    private static TsonSchemaParser compile(TupleBody body) {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("integer", new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), false, List.of(),
                List.of(), Optional.empty(), IntegerType.UNCONSTRAINED));
        entries.put("text", new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), false, List.of(),
                List.of(), Optional.empty(), TextType.UNCONSTRAINED));
        entries.put("pair", TypeDefinition.product(body));
        TsonSchema schema = new TsonSchema(Optional.of("https://example.test/s.tn1"),
                "https://example.test/meta.tn1", List.of(), entries);
        return TsonSchemaParser.compile(schema, registry());
    }

    @SuppressWarnings("unchecked")
    private static List<Object> readTuple(TsonSchemaParser compiled, String source) {
        Document document = new Parser(source).parseDocument();
        return (List<Object>) compiled.get("pair").read(document.root());
    }

    private static TupleBody twoRequiredSlots() {
        return new TupleBody(List.of(
                TupleElement.required(TypeRef.of("integer")),
                TupleElement.required(TypeRef.of("text"))));
    }

    @Test
    void readsAHeterogeneousTuple() {
        TsonSchemaParser compiled = compile(twoRequiredSlots());

        assertEquals(List.of(BigInteger.valueOf(42), "hello"), readTuple(compiled, "[42 hello]"));
    }

    @Test
    void wrongArityThrows() {
        TsonSchemaParser compiled = compile(twoRequiredSlots());

        assertThrows(IllegalArgumentException.class, () -> readTuple(compiled, "[42]"));
        assertThrows(IllegalArgumentException.class, () -> readTuple(compiled, "[42 hello extra]"));
    }

    @Test
    void recordShapedDataIsRejected() {
        // A tuple is array-shaped on the wire -- {} is never a plausible reading, unlike record/map.
        TsonSchemaParser compiled = compile(twoRequiredSlots());

        assertThrows(IllegalArgumentException.class, () -> readTuple(compiled, "{}"));
    }

    @Test
    void optionalPositionToleratesTheAbsentSentinel() {
        TupleBody body = new TupleBody(List.of(
                TupleElement.required(TypeRef.of("integer")),
                new TupleElement(TypeRef.of("text"), ElementState.OPTIONAL)));
        TsonSchemaParser compiled = compile(body);

        List<Object> result = readTuple(compiled, "[42 _]");
        assertEquals(BigInteger.valueOf(42), result.get(0));
        assertNull(result.get(1));
    }

    @Test
    void requiredPositionRejectsTheAbsentSentinel() {
        TsonSchemaParser compiled = compile(twoRequiredSlots());

        assertThrows(IllegalArgumentException.class, () -> readTuple(compiled, "[42 _]"));
    }
}
