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
        return new TsonSchema("https://example.test/registry-test.tn1",
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

    /**
     * {@code id} is required now (2026-07-26, on the user's own explicit direction) -- a schema with
     * no {@code !!id} can no longer be constructed at all, so the rejection this test used to prove
     * at {@code register}-time is now a construction-time {@link NullPointerException} instead; see
     * {@code TsonSchema}'s own Javadoc for why.
     */
    @Test
    void constructingASchemaWithNoIdIsRejected() {
        assertThrows(NullPointerException.class,
                () -> new TsonSchema(null, "https://example.test/meta.tn1", List.of(), Map.of()));
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
                "http://example.test/registry-test.tn1", "https://example.test/meta.tn1",
                List.of(), Map.of());

        assertThrows(SchemaValidationException.class, () -> registry.register(sameIdentityDifferentScheme));
    }

    @Test
    void validateIdentityAcceptsAWellFormedCandidateSilently() {
        SchemaRegistry.validateIdentity("https://example.test/registry-test.tn1");
        // No exception -- that's the whole assertion.
    }

    @Test
    void validateIdentityRejectsAUriWithNoScheme() {
        assertThrows(SchemaValidationException.class, () -> SchemaRegistry.validateIdentity("registry-test.tn1"));
    }

    @Test
    void validateIdentityRejectsAUriCarryingAPort() {
        assertThrows(SchemaValidationException.class,
                () -> SchemaRegistry.validateIdentity("https://example.test:8080/registry-test.tn1"));
    }
}
