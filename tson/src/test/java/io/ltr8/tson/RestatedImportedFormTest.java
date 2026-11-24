package io.ltr8.tson;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ltr8.tson.base.SchemaValidationException;

/**
 * A schema restating a form an import writes ([TSON-SCHEMA] §8.2): a type written in place is no entry, and a
 * construction a declaration names is that declaration's own, so a schema writing {@code [meta.type_ref]} beside
 * meta.tn's, or declaring {@code set<text>} beside core.tn, links. A name an import declares is still the collision
 * §2.2.3 refuses.
 */
class RestatedImportedFormTest {

    private static final String META = "https://tson.io/2026/40/m/meta.tn";
    private static final String CORE = "https://tson.io/2026/40/m/core.tn";
    private static final String KERNEL = "https://tson.io/2026/40/m/meta.tn";

    /** meta.tn writes {@code [meta.type_ref]} in place; a declaration of the same form is this schema's own. */
    @Test
    void aFormAnImportWritesInPlaceIsDeclaredWithoutACollision() {
        assertDoesNotThrow(() -> Tson.standard().resolve("""
                !!id:"https://example.test/restated-form.tn"
                !!import:"%s"
                !!import:"%s"
                !schema {
                  mine => [meta.type_ref]
                }
                """.formatted(KERNEL, META)));
    }

    /** The same one layer down, through core.tn's {@code set}. */
    @Test
    void aConsumerConstructingWithItsImportsConstructorLinks() {
        assertDoesNotThrow(() -> Tson.standard().resolve("""
                !!id:"https://example.test/restated-form-core.tn"
                !!import:"%s"
                !!import:"%s"
                !schema {
                  tags => set<text>
                  box  => { a: tags }
                }
                """.formatted(META, CORE)));
    }

    /** A name an import already declares is an error. */
    @Test
    void aLocalDeclarationShadowingAnImportedNameCollides() {
        SchemaValidationException thrown = assertThrows(SchemaValidationException.class,
                () -> Tson.standard().resolve("""
                        !!id:"https://example.test/shadowing.tn"
                        !!import:"%s"
                        !!import:"%s"
                        !schema {
                          int32 => { a: text }
                        }
                        """.formatted(META, CORE)));

        assertTrue(thrown.getMessage().contains("'int32' collides"), thrown.getMessage());
    }
}
