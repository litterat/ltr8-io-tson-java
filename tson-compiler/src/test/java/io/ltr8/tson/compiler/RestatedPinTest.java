package io.ltr8.tson.compiler;

import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.schema.TsonLinkedSchema;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [TSON-SCHEMA] §5.8: a restatement may change a default and MUST NOT change a pin, the two compared under the
 * field type's own equality (§5.5).
 */
class RestatedPinTest {

    private static final String ID = "https://example.test/pin.tn";

    private static TsonLinkedSchema link(String body) {
        String schema = """
                !!id:"https://example.test/pin.tn"
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !!import:"https://tson.io/2026/40/m/core.tn"
                !schema
                """ + body;
        return TsonCompiledVocabularyRegistry.withStandardLibrary(SchemaModelNameBinder.defaultContext(), uri -> {
            if (CanonicalIdentity.sameIdentity(uri, ID)) {
                return schema;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        }).resolveLinked(ID);
    }

    private static String refused(String body) {
        return assertThrows(SchemaValidationException.class, () -> link(body)).getMessage();
    }

    @Test
    void aRestatementChangingAPinIsRefused() {
        String message = refused("""
                {
                  base => { name: text  pet_type: text = "pet" }
                  dog  => base & { pet_type: = "dog" }
                }
                """);

        assertTrue(message.contains("'dog': not a valid restatement of 'base''s fields (§5.8): field 'pet_type' is "
                + "pinned to \"pet\" and restated as \"dog\""), message);
    }

    @Test
    void aPinChangedFurtherDownAChainIsRefused() {
        String message = refused("""
                {
                  base  => { kind?: text ~ "any" }
                  dog   => base & { kind?: = "dog" }
                  puppy => dog & { kind?: = "puppy" }
                }
                """);

        assertTrue(message.contains("'puppy': not a valid restatement of 'dog''s fields (§5.8): field 'kind' is "
                + "pinned to \"dog\" and restated as \"puppy\""), message);
    }

    @Test
    void aPinAbsorbedFromAnApplicationIsNotChanged() {
        String message = refused("""
                {
                  boxed => <T> { v: T  tag: text = "box" }
                  other => boxed<int32> & { tag: = "other" }
                }
                """);

        assertTrue(message.contains("'other': not a valid restatement of 'boxed<int32>''s fields (§5.8): field "
                + "'tag' is pinned to \"box\" and restated as \"other\""), message);
    }

    /** A pin under a type naming no parameter is every application's, so it is judged at the declaration (§5.10). */
    @Test
    void aParametricRecordsChangedPinIsRefusedAtItsDeclarationAppliedOrNot() {
        String records = """
                  base  => { tag: text = "base" }
                  boxed => <T> base & { v: T  tag: = "boxed" }
                """;
        for (String applier : new String[] {"", "  b     => boxed<int32>\n"}) {
            String message = refused("{\n" + records + applier + "}\n");

            assertTrue(message.contains("'boxed': not a valid restatement of 'base''s fields (§5.8): field 'tag' is "
                    + "pinned to \"base\" and restated as \"boxed\""), message);
        }
    }

    @Test
    void aPinChangedOverAnApplicationOverAParameterIsRefusedAtTheDeclaration() {
        String message = refused("""
                {
                  box  => <T> { v: T  tag: text = "box" }
                  wrap => <T> box<T> & { tag: = "x" }
                }
                """);

        assertTrue(message.contains("'wrap': not a valid restatement of 'box<T>''s fields (§5.8): field 'tag' is "
                + "pinned to \"box\" and restated as \"x\""), message);
    }

    /** Whether a restated type narrows can turn on the argument, so it stays judged where the application closes. */
    @Test
    void aRestatedTypeOverAParameterIsNotJudgedAtTheDeclaration() {
        assertDoesNotThrow(() -> link("""
                {
                  box  => <T> { v: T }
                  wrap => <T> box<T> & { v: int32 }
                  ok   => wrap<int32>
                }
                """));
    }

    @Test
    void theIdentityRestatementIsAdmittedUnderTheFieldTypesEquality() {
        assertDoesNotThrow(() -> link("""
                {
                  base    => { pet_type: text = "pet"  mask: uint8 = 0xFF  kind?: text ~ "any" }
                  same    => base & { pet_type: = pet  mask: = 255 }
                  retyped => base & { pet_type: text = "pet" }
                  dog     => base & { kind?: = "dog" }
                }
                """));
    }
}
