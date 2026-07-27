package io.ltr8.tson.parser.compiler;

import io.ltr8.tson.parser.TsonDataParser;
import io.ltr8.tson.parser.ast.Document;
import io.ltr8.tson.parser.atom.AtomValidationException;
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
 * End-to-end proof of the compiled-schema-parser sketch against the real {@link RecordParser} and
 * {@link AtomTypeParser#INTEGER_TYPE} -- a real (hand-built, but shaped exactly like a materialized
 * {@link TsonSchema} would be) schema compiled with a real registry, read against real TSON data
 * source text through the real {@link TsonDataParser}. Supersedes the minimal map-producing stand-in this
 * class used before {@link RecordParser} existed -- same two original cases kept (now against the
 * real factory), plus new coverage for {@link RecordParser}'s own state/defaulting behavior.
 */
class RecordParserTest {

    private static TsonSchema pointSchema(TypeDefinition integerEntry, RecordField valueField) {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("integer", integerEntry);
        entries.put("point", TypeDefinition.product(RecordBody.of(List.of(valueField))));
        return new TsonSchema("test-schema", "test-meta", List.of(), entries);
    }

    private static TypeDefinition atomEntry(IntegerType body) {
        return new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), false, List.of(), List.of(),
                Optional.empty(), body);
    }

    private static TsonParserFactoryRegistry registry() {
        return TsonParserFactoryRegistry.builder()
                .register("integer_type", AtomTypeParser.INTEGER_TYPE)
                .register("record", RecordParser.FACTORY)
                .build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(TsonCompiledSchema compiled, String source) {
        Document document = new TsonDataParser(source).parseDocument();
        return (Map<String, Object>) compiled.get("point").read(document.root());
    }

    @Test
    void unconstrainedIntegerFieldReadsFromRealDataText() {
        TsonCompiledSchema compiled = TsonSchemaCompiler.compile(
                pointSchema(atomEntry(IntegerType.UNCONSTRAINED), RecordField.required("value", TypeRef.of("integer"))),
                registry());

        assertEquals(BigInteger.valueOf(42), read(compiled, "{ value: 42 }").get("value"));
    }

    @Test
    void constrainedIntegerFieldValidatesAgainstItsOwnDeclaredRange() {
        TsonCompiledSchema compiled = TsonSchemaCompiler.compile(
                pointSchema(atomEntry(new IntegerType(new IntegerSize(8, true))),
                        RecordField.required("value", TypeRef.of("integer"))),
                registry());

        assertEquals((byte) 100, read(compiled, "{ value: 100 }").get("value"));
        assertThrows(AtomValidationException.class, () -> read(compiled, "{ value: 200 }"));
    }

    @Test
    void missingRequiredFieldThrows() {
        TsonCompiledSchema compiled = TsonSchemaCompiler.compile(
                pointSchema(atomEntry(IntegerType.UNCONSTRAINED), RecordField.required("value", TypeRef.of("integer"))),
                registry());

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> read(compiled, "{}"));
        assertTrue(thrown.getMessage().contains("value"), thrown.getMessage());
    }

    @Test
    void absentOptionalFieldReadsAsNull() {
        RecordField optional = new RecordField("value", TypeRef.of("integer"), FieldState.OPTIONAL,
                Optional.empty(), Optional.empty());
        TsonCompiledSchema compiled = TsonSchemaCompiler.compile(
                pointSchema(atomEntry(IntegerType.UNCONSTRAINED), optional), registry());

        assertNull(read(compiled, "{}").get("value"));
        assertNull(read(compiled, "{ value: _ }").get("value"));
    }

    @Test
    void requiredDefaultFieldFillsFromTheSchemaWhenAbsentButExplicitValueStillWins() {
        RecordField defaulted = new RecordField("value", TypeRef.of("integer"), FieldState.REQUIRED_DEFAULT,
                Optional.of(new Token("7", Token.Form.UNQUOTED)), Optional.empty());
        TsonCompiledSchema compiled = TsonSchemaCompiler.compile(
                pointSchema(atomEntry(IntegerType.UNCONSTRAINED), defaulted), registry());

        assertEquals(BigInteger.valueOf(7), read(compiled, "{}").get("value"));
        assertEquals(BigInteger.valueOf(9), read(compiled, "{ value: 9 }").get("value"));
    }
}
