package io.ltr8.tson.parser.resolver.schema.compiled;

import io.ltr8.tson.parser.Parser;
import io.ltr8.tson.parser.ast.Document;
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
 * End-to-end proof of {@link ChoiceParser} -- a {@code contact_method => (email | phone)}-style
 * closed union, the explicit-list counterpart to {@link VariantParserTest}'s open one.
 */
class ChoiceParserTest {

    private static TsonSchemaParser compiled() {
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

        ParserFactoryRegistry registry = ParserFactoryRegistry.builder()
                .register("integer_type", AtomTypeParser.INTEGER_TYPE)
                .register("record", RecordParser.FACTORY)
                .register("choice", ChoiceParser.FACTORY)
                .build();
        return TsonSchemaParser.compile(schema, registry);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(TsonSchemaParser compiled, String source) {
        Document document = new Parser(source).parseDocument();
        return (Map<String, Object>) compiled.get("contact_method").read(document.root());
    }

    @Test
    void dispatchesToTheVariantNamedByTheValuesOwnTypeRef() {
        TsonSchemaParser compiled = compiled();

        assertEquals(BigInteger.valueOf(1), read(compiled, "!email { address: 1 }").get("address"));
        assertEquals(BigInteger.valueOf(2), read(compiled, "!phone { number: 2 }").get("number"));
    }

    @Test
    void missingTypeRefThrowsNamingTheDeclaredVariants() {
        TsonSchemaParser compiled = compiled();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> read(compiled, "{ address: 1 }"));
        assertTrue(thrown.getMessage().contains("email"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("phone"), thrown.getMessage());
    }

    @Test
    void undeclaredVariantThrows() {
        TsonSchemaParser compiled = compiled();

        assertThrows(IllegalArgumentException.class, () -> read(compiled, "!fax { address: 1 }"));
    }
}
