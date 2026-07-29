package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonCompiledMetaSchema;
import io.ltr8.tson.compiler.TsonCompiledSchema;
import io.ltr8.tson.compiler.TsonDataParser;
import io.ltr8.tson.compiler.TsonSchemaCompiler;
import io.ltr8.tson.compiler.ast.Document;
import io.ltr8.tson.compiler.atom.AtomValidationException;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.FieldState;
import io.ltr8.tson.schema.meta.IntegerSize;
import io.ltr8.tson.schema.meta.IntegerType;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Token;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof of the compiled-schema-reader sketch against the real {@link RecordDomReader} and
 * {@link AtomValueReader#INTEGER_TYPE} -- a real (hand-built, but shaped exactly like a materialized
 * {@link TsonSchema} would be) schema compiled with the real DOM-mode factory registry, read against
 * real TSON data source text through the real {@link TsonDataParser}.
 */
class RecordDomReaderTest {

    private static TsonLinkedSchema pointSchema(TypeDefinition integerEntry, RecordField valueField) {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("integer", integerEntry);
        entries.put("point", TypeDefinition.product(RecordBody.of(List.of(valueField))));
        return new TsonLinkedSchema(new TsonSchema("test-schema", "test-meta", List.of(), entries));
    }

    private static TypeDefinition atomEntry(IntegerType body) {
        return new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), false, List.of(), List.of(),
                Optional.empty(), body);
    }

    private static TsonCompiledSchema compile(TsonLinkedSchema linkedSchema) {
        TsonCompiledSchema placeholder = new TsonCompiledSchema(linkedSchema, Map.of());
        TsonCompiledMetaSchema bootstrapMeta = new TsonCompiledMetaSchema(placeholder, ValueReaderFactoryRegistry.dom());
        return TsonSchemaCompiler.compile(linkedSchema, bootstrapMeta);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(TsonCompiledSchema compiled, String source) {
        Document document = new TsonDataParser(source).parseDocument();
        return (Map<String, Object>) compiled.get("point").read(document.root());
    }

    @Test
    void unconstrainedIntegerFieldReadsFromRealDataText() {
        TsonCompiledSchema compiled = compile(
                pointSchema(atomEntry(IntegerType.UNCONSTRAINED), RecordField.required("value", TypeRef.of("integer"))));

        assertEquals(BigInteger.valueOf(42), read(compiled, "{ value: 42 }").get("value"));
    }

    @Test
    void constrainedIntegerFieldValidatesAgainstItsOwnDeclaredRange() {
        TsonCompiledSchema compiled = compile(
                pointSchema(atomEntry(new IntegerType(new IntegerSize(8, true))),
                        RecordField.required("value", TypeRef.of("integer"))));

        assertEquals((byte) 100, read(compiled, "{ value: 100 }").get("value"));
        assertThrows(AtomValidationException.class, () -> read(compiled, "{ value: 200 }"));
    }

    @Test
    void missingRequiredFieldThrows() {
        TsonCompiledSchema compiled = compile(
                pointSchema(atomEntry(IntegerType.UNCONSTRAINED), RecordField.required("value", TypeRef.of("integer"))));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> read(compiled, "{}"));
        assertTrue(thrown.getMessage().contains("value"), thrown.getMessage());
    }

    @Test
    void absentOptionalFieldReadsAsNull() {
        RecordField optional = new RecordField("value", TypeRef.of("integer"), FieldState.OPTIONAL,
                Optional.empty(), Optional.empty());
        TsonCompiledSchema compiled = compile(pointSchema(atomEntry(IntegerType.UNCONSTRAINED), optional));

        assertNull(read(compiled, "{}").get("value"));
        assertNull(read(compiled, "{ value: _ }").get("value"));
    }

    @Test
    void requiredDefaultFieldFillsFromTheSchemaWhenAbsentButExplicitValueStillWins() {
        RecordField defaulted = new RecordField("value", TypeRef.of("integer"), FieldState.REQUIRED_DEFAULT,
                Optional.of(new Token("7", Token.Form.UNQUOTED)), Optional.empty());
        TsonCompiledSchema compiled = compile(pointSchema(atomEntry(IntegerType.UNCONSTRAINED), defaulted));

        assertEquals(BigInteger.valueOf(7), read(compiled, "{}").get("value"));
        assertEquals(BigInteger.valueOf(9), read(compiled, "{ value: 9 }").get("value"));
    }
}
