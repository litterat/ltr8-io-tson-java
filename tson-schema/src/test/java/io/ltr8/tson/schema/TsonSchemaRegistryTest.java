package io.ltr8.tson.schema;

import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TypeArgument;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.Unit;
import io.ltr8.tson.schema.TsonSchemaLinker;
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

    /** Every {@code register} call site needs a linked schema now -- see {@code TsonSchemaRegistry}'s own Javadoc. */
    private static TsonLinkedSchema linkedSchemaWithGenericField(TsonSchemaRegistry registry) {
        return TsonSchemaLinker.link(schemaWithGenericField(), registry);
    }

    @Test
    void registerRunsLinkingAndTheResultIsFindableByItsRawId() {
        TsonSchemaRegistry registry = new TsonSchemaRegistry();
        TsonLinkedSchema registered = registry.register(linkedSchemaWithGenericField(registry));

        // The generic field got materialized by TsonSchemaLinker.link, before register was ever called.
        assertEquals(4, registered.schema().entries().size(), "one synthetic entry beyond the original three");

        Optional<TsonLinkedSchema> found = registry.get("https://example.test/registry-test.tn1");
        assertTrue(found.isPresent());
        assertEquals(registered.schema().entries().keySet(), found.get().schema().entries().keySet());
    }

    @Test
    void aDifferentSchemeFindsTheSameRegisteredSchema() {
        TsonSchemaRegistry registry = new TsonSchemaRegistry();
        registry.register(linkedSchemaWithGenericField(registry));

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
        registry.register(linkedSchemaWithGenericField(registry));

        assertThrows(TsonSchemaValidationException.class,
                () -> registry.register(linkedSchemaWithGenericField(registry)));
    }

    @Test
    void rejectsRegisteringTheSameIdentityTwiceEvenUnderADifferentScheme() {
        TsonSchemaRegistry registry = new TsonSchemaRegistry();
        registry.register(linkedSchemaWithGenericField(registry));

        TsonSchema sameIdentityDifferentScheme = new TsonSchema(
                "http://example.test/registry-test.tn1", "https://example.test/meta.tn1",
                List.of(), Map.of());
        TsonLinkedSchema linked = TsonSchemaLinker.link(sameIdentityDifferentScheme, registry);

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
