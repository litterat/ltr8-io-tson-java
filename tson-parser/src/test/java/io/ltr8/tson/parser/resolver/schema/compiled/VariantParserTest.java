package io.ltr8.tson.parser.resolver.schema.compiled;

import io.ltr8.tson.parser.Parser;
import io.ltr8.tson.parser.ast.Document;
import io.ltr8.tson.schema.SchemaRegistry;
import io.ltr8.tson.schema.TsonSchema;
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
 * End-to-end proof of {@link VariantParser}: the exact {@code response}/{@code success_response}/
 * {@code failure_response} case this class was built for -- {@code response} has open type
 * parameters and an empty body of its own, so it's never readable directly; real data always
 * arrives tagged as one of its two concrete subtypes.
 */
class VariantParserTest {

    private static TsonSchema compileableSchema() {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("integer", new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), false,
                List.of(), List.of(), Optional.empty(), IntegerType.UNCONSTRAINED));
        entries.put("response", new TypeDefinition(Optional.empty(), TypeKind.PRODUCT, List.of("T"), false,
                List.of(), List.of(), Optional.empty(), RecordBody.of(List.of())));
        entries.put("success_response", new TypeDefinition(Optional.empty(), TypeKind.PRODUCT, List.of(), false,
                List.of("response"), List.of(), Optional.empty(),
                RecordBody.of(List.of(RecordField.required("value", TypeRef.of("integer"))))));
        entries.put("failure_response", new TypeDefinition(Optional.empty(), TypeKind.PRODUCT, List.of(), false,
                List.of("response"), List.of(), Optional.empty(),
                RecordBody.of(List.of(RecordField.required("error_code", TypeRef.of("integer"))))));
        return new TsonSchema(Optional.of("https://example.test/s.tn1"), "https://example.test/meta.tn1",
                List.of(), entries);
    }

    private static ParserFactoryRegistry registry() {
        return ParserFactoryRegistry.builder()
                .register("integer_type", IntegerTypeParserFactory.FACTORY)
                .register("record", RecordParser.FACTORY)
                .build();
    }

    private static TsonSchemaParser compiled() {
        TsonSchema registered = new SchemaRegistry().register(compileableSchema());
        return TsonSchemaParser.compile(registered, registry());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(TsonSchemaParser compiled, String source) {
        Document document = new Parser(source).parseDocument();
        return (Map<String, Object>) compiled.get("response").read(document.root());
    }

    @Test
    void dispatchesToTheSubtypeNamedByTheValuesOwnTypeRef() {
        TsonSchemaParser compiled = compiled();

        assertEquals(BigInteger.valueOf(42), read(compiled, "!success_response { value: 42 }").get("value"));
        assertEquals(BigInteger.valueOf(404), read(compiled, "!failure_response { error_code: 404 }").get("error_code"));
    }

    @Test
    void missingTypeRefThrowsNamingTheKnownSubtypes() {
        TsonSchemaParser compiled = compiled();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> read(compiled, "{ value: 42 }"));
        assertTrue(thrown.getMessage().contains("success_response"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("failure_response"), thrown.getMessage());
    }

    @Test
    void unknownTypeRefThrowsNamingTheOffendingValue() {
        TsonSchemaParser compiled = compiled();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> read(compiled, "!partial_response { value: 42 }"));
        assertTrue(thrown.getMessage().contains("partial_response"), thrown.getMessage());
    }
}
