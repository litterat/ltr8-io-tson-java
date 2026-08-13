package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonCompiledSchema;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonReadException;
import io.ltr8.tson.compiler.TsonSchemaCompiler;
import io.ltr8.tson.schema.TsonLinkedSchema;
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

/** End-to-end proof of {@link TupleTreeReader} against real TSON data source text -- a heterogeneous (integer, text) pair. */
class TupleTreeReaderTest {

    private static TsonCompiledSchema compile(TupleBody body) {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("integer", new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), false, List.of(),
                List.of(), Optional.empty(), IntegerType.UNCONSTRAINED));
        entries.put("text", new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), false, List.of(),
                List.of(), Optional.empty(), TextType.UNCONSTRAINED));
        entries.put("pair", TypeDefinition.product(body));
        TsonSchema schema = new TsonSchema("https://example.test/s.tn1",
                "https://example.test/meta.tn1", List.of(), entries);
        TsonLinkedSchema linkedSchema = new TsonLinkedSchema(schema);
        return TsonSchemaCompiler.compile(linkedSchema, ValueReaderFactoryRegistry.tree());
    }

    @SuppressWarnings("unchecked")
    private static List<Object> readTuple(TsonCompiledSchema compiled, String source) {
        return (List<Object>) Dom.of((io.ltr8.tson.tree.TsonNode) compiled.get("pair")
                .read(TsonReadContext.document(source)));
    }

    private static TupleBody twoRequiredSlots() {
        return new TupleBody(List.of(
                TupleElement.required(TypeRef.of("integer")),
                TupleElement.required(TypeRef.of("text"))));
    }

    @Test
    void readsAHeterogeneousTuple() {
        TsonCompiledSchema compiled = compile(twoRequiredSlots());

        assertEquals(List.of(BigInteger.valueOf(42), "hello"), readTuple(compiled, "[42 hello]"));
    }

    @Test
    void wrongArityThrows() {
        TsonCompiledSchema compiled = compile(twoRequiredSlots());

        assertThrows(TsonReadException.class, () -> readTuple(compiled, "[42]"));
        assertThrows(TsonReadException.class, () -> readTuple(compiled, "[42 hello extra]"));
    }

    @Test
    void recordShapedDataIsRejected() {
        // A tuple is array-shaped on the wire -- {} is never a plausible reading, unlike record/map.
        TsonCompiledSchema compiled = compile(twoRequiredSlots());

        assertThrows(TsonReadException.class, () -> readTuple(compiled, "{}"));
    }

    @Test
    void optionalPositionToleratesTheAbsentSentinel() {
        TupleBody body = new TupleBody(List.of(
                TupleElement.required(TypeRef.of("integer")),
                new TupleElement(TypeRef.of("text"), ElementState.OPTIONAL)));
        TsonCompiledSchema compiled = compile(body);

        List<Object> result = readTuple(compiled, "[42 _]");
        assertEquals(BigInteger.valueOf(42), result.get(0));
        assertNull(result.get(1));
    }

    @Test
    void requiredPositionRejectsTheAbsentSentinel() {
        TsonCompiledSchema compiled = compile(twoRequiredSlots());

        assertThrows(TsonReadException.class, () -> readTuple(compiled, "[42 _]"));
    }
}
