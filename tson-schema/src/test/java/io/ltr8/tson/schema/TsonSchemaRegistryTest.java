package io.ltr8.tson.schema;

import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
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

class TsonSchemaRegistryTest {

    private static TsonSchema schemaWithARecordField() {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("token", new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), false, List.of(),
                List.of(), Optional.empty(), new Unit()));
        entries.put("set_token", TypeDefinition.product(RecordBody.of(List.of())));
        entries.put("container", TypeDefinition.product(RecordBody.of(List.of(
                RecordField.required("members", TypeRef.of("set_token"))))));
        return new TsonSchema("https://example.test/registry-test.tn1",
                "https://example.test/meta.tn1", List.of(), entries);
    }

    /**
     * {@code register} accepts only a linked schema, so every call site needs one. These tests are about the
     * registry's own storage and identity behaviour, not about linking, so they wrap directly rather than
     * running {@code TsonSchemaLinker} (which lives in {@code tson-compiler}, a module this one cannot reach);
     * {@code TsonSchemaLinkerTest} covers what linking itself does.
     */
    private static TsonLinkedSchema linkedSchema() {
        return new TsonLinkedSchema(schemaWithARecordField());
    }

    @Test
    void registerStoresTheSchemaAndTheResultIsFindableByItsRawId() {
        TsonSchemaRegistry registry = new TsonSchemaRegistry();
        TsonLinkedSchema registered = registry.register(linkedSchema());

        // register only stores what it was handed, entries untouched.
        assertEquals(3, registered.schema().entries().size());

        Optional<TsonLinkedSchema> found = registry.get("https://example.test/registry-test.tn1");
        assertTrue(found.isPresent());
        assertEquals(registered.schema().entries().keySet(), found.get().schema().entries().keySet());
    }

    @Test
    void aDifferentSchemeFindsTheSameRegisteredSchema() {
        TsonSchemaRegistry registry = new TsonSchemaRegistry();
        registry.register(linkedSchema());

        assertTrue(registry.get("http://example.test/registry-test.tn1").isPresent());
    }

    @Test
    void getReturnsEmptyForAnUnregisteredIdentity() {
        TsonSchemaRegistry registry = new TsonSchemaRegistry();
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
        TsonSchemaRegistry registry = new TsonSchemaRegistry();
        registry.register(linkedSchema());

        assertThrows(TsonSchemaValidationException.class,
                () -> registry.register(linkedSchema()));
    }

    @Test
    void rejectsRegisteringTheSameIdentityTwiceEvenUnderADifferentScheme() {
        TsonSchemaRegistry registry = new TsonSchemaRegistry();
        registry.register(linkedSchema());

        TsonSchema sameIdentityDifferentScheme = new TsonSchema(
                "http://example.test/registry-test.tn1", "https://example.test/meta.tn1",
                List.of(), Map.of());
        TsonLinkedSchema linked = new TsonLinkedSchema(sameIdentityDifferentScheme);

        assertThrows(TsonSchemaValidationException.class, () -> registry.register(linked));
    }

    @Test
    void validateIdentityAcceptsAWellFormedCandidateSilently() {
        TsonSchemaRegistry.validateIdentity("https://example.test/registry-test.tn1");
        // No exception -- that's the whole assertion.
    }

    @Test
    void validateIdentityRejectsAUriWithNoScheme() {
        assertThrows(TsonSchemaValidationException.class, () -> TsonSchemaRegistry.validateIdentity("registry-test.tn1"));
    }

    @Test
    void validateIdentityRejectsAUriCarryingAPort() {
        assertThrows(TsonSchemaValidationException.class,
                () -> TsonSchemaRegistry.validateIdentity("https://example.test:8080/registry-test.tn1"));
    }

    @Test
    void canonicalIdentityStripsSchemeAndQuery() {
        assertEquals("example.test/registry-test.tn1",
                TsonSchemaRegistry.canonicalIdentity("https://example.test/registry-test.tn1"));
    }

    @Test
    void aSha256PinDoesNotChangeTheIdentity() {
        // The hash is verification metadata, not identity -- a pinned and a plain reference match.
        assertEquals(
                TsonSchemaRegistry.canonicalIdentity("https://example.test/registry-test.tn1"),
                TsonSchemaRegistry.canonicalIdentity("https://example.test/registry-test.tn1?sha256=" + "a".repeat(64)));
    }
}
