package io.ltr8.tson.schema;

import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TypeArgument;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.Unit;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaRegistryTest {

    private static TsonSchema schemaWithGenericField() {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("token", new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), false, List.of(),
                List.of(), Optional.empty(), new Unit()));
        entries.put("set", TypeDefinition.product(RecordBody.of(List.of())));
        entries.put("container", TypeDefinition.product(RecordBody.of(List.of(
                RecordField.required("members", new TypeRef("set", List.of(new TypeArgument.Ref(TypeRef.of("token")))))))));
        return new TsonSchema(Optional.of("https://example.test/registry-test.tn1"),
                "https://example.test/meta.tn1", List.of(), entries);
    }

    @Test
    void registerRunsValidationAndTheResultIsFindableByItsRawId() {
        SchemaRegistry registry = new SchemaRegistry();
        TsonSchema registered = registry.register(schemaWithGenericField());

        // register() actually ran SchemaValidator -- the generic field got materialized.
        assertEquals(4, registered.entries().size(), "one synthetic entry beyond the original three");

        Optional<TsonSchema> found = registry.get("https://example.test/registry-test.tn1");
        assertTrue(found.isPresent());
        assertEquals(registered.entries().keySet(), found.get().entries().keySet());
    }

    @Test
    void aDifferentSchemeFindsTheSameRegisteredSchema() {
        SchemaRegistry registry = new SchemaRegistry();
        registry.register(schemaWithGenericField());

        assertTrue(registry.get("http://example.test/registry-test.tn1").isPresent());
    }

    @Test
    void getReturnsEmptyForAnUnregisteredIdentity() {
        SchemaRegistry registry = new SchemaRegistry();
        assertFalse(registry.get("https://example.test/never-registered.tn1").isPresent());
    }

    @Test
    void rejectsRegisteringASchemaWithNoId() {
        TsonSchema noId = new TsonSchema(Optional.empty(), "https://example.test/meta.tn1", List.of(), Map.of());
        SchemaRegistry registry = new SchemaRegistry();

        assertThrows(SchemaValidationException.class, () -> registry.register(noId));
    }

    @Test
    void rejectsRegisteringTheSameIdentityTwice() {
        SchemaRegistry registry = new SchemaRegistry();
        registry.register(schemaWithGenericField());

        assertThrows(SchemaValidationException.class, () -> registry.register(schemaWithGenericField()));
    }

    @Test
    void rejectsRegisteringTheSameIdentityTwiceEvenUnderADifferentScheme() {
        SchemaRegistry registry = new SchemaRegistry();
        registry.register(schemaWithGenericField());

        TsonSchema sameIdentityDifferentScheme = new TsonSchema(
                Optional.of("http://example.test/registry-test.tn1"), "https://example.test/meta.tn1",
                List.of(), Map.of());

        assertThrows(SchemaValidationException.class, () -> registry.register(sameIdentityDifferentScheme));
    }
}
