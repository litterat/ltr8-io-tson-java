package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonCompiledMetaSchema;
import io.ltr8.tson.compiler.TsonCompiledSchema;
import io.ltr8.tson.compiler.TsonReadException;
import io.ltr8.tson.compiler.TsonSchemaCompiler;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.IntegerType;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
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

/**
 * End-to-end proof of {@link ChoiceReader} -- a {@code contact_method => (email | phone)}-style
 * closed union, the explicit-list counterpart to {@link VariantDomReaderTest}'s open one.
 */
class ChoiceReaderTest {

    private static TsonCompiledSchema compiled() {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("integer", new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), false,
                List.of(), List.of(), Optional.empty(), IntegerType.UNCONSTRAINED));
        entries.put("email", TypeDefinition.product(
                RecordBody.of(List.of(RecordField.required("address", TypeRef.of("integer"))))));
        entries.put("phone", TypeDefinition.product(
                RecordBody.of(List.of(RecordField.required("number", TypeRef.of("integer"))))));
        entries.put("contact_method", TypeDefinition.product(
                new ChoiceBody(List.of(TypeRef.of("email"), TypeRef.of("phone")))));
        TsonSchema schema = new TsonSchema("https://example.test/s.tn1",
                "https://example.test/meta.tn1", List.of(), entries);
        TsonLinkedSchema linkedSchema = new TsonLinkedSchema(schema);

        TsonCompiledSchema placeholder = new TsonCompiledSchema(linkedSchema, Map.of());
        TsonCompiledMetaSchema bootstrapMeta = new TsonCompiledMetaSchema(placeholder, ValueReaderFactoryRegistry.dom());
        return TsonSchemaCompiler.compile(linkedSchema, bootstrapMeta);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(TsonCompiledSchema compiled, String source) {
        return (Map<String, Object>) compiled.get("contact_method").read(source);
    }

    @Test
    void dispatchesToTheVariantNamedByTheValuesOwnTypeRef() {
        TsonCompiledSchema compiled = compiled();

        assertEquals(BigInteger.valueOf(1), read(compiled, "!email { address: 1 }").get("address"));
        assertEquals(BigInteger.valueOf(2), read(compiled, "!phone { number: 2 }").get("number"));
    }

    @Test
    void missingTypeRefThrowsNamingTheDeclaredVariants() {
        TsonCompiledSchema compiled = compiled();

        TsonReadException thrown = assertThrows(TsonReadException.class,
                () -> read(compiled, "{ address: 1 }"));
        assertTrue(thrown.getMessage().contains("email"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("phone"), thrown.getMessage());
    }

    @Test
    void undeclaredVariantThrows() {
        TsonCompiledSchema compiled = compiled();

        assertThrows(TsonReadException.class, () -> read(compiled, "!fax { address: 1 }"));
    }
}
