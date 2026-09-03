package io.ltr8.tson;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ltr8.tson.schema.TsonSchemaValidationException;

/**
 * [TSON-SCHEMA] §8.2's instantiation identity across an {@code !!import} boundary: two applications of one
 * template with one argument are <b>one entry</b>, wherever they are written -- "two {@code box<text>}
 * anywhere share one entry". A materialised instantiation's name is a function of its resolved form alone,
 * so a schema that closes an application an import has already closed reaches the same name with the same
 * body, and the two unify.
 *
 * <p>Without that, exporting a template is exporting a trap: any consumer applying it with an argument the
 * exporting schema also used fails to link, with a name-collision diagnostic naming a synthetic entry the
 * author never wrote. meta.tn applies {@code set<value>} for {@code decimal_member_set}, so the reachable
 * case is a schema importing meta.tn and writing {@code set<value>} of its own.
 *
 * <p>The negative half is the same rule from the other side and is what keeps the unification honest: name
 * equality is not the test, entry equality is. Two <em>different</em> types under one name still collide.
 */
class SharedInstantiationTest {

    private static final String META = "https://tson.io/2026/35/m/meta.tn";
    private static final String CORE = "https://tson.io/2026/35/m/core.tn";
    private static final String KERNEL = "https://tson.io/2026/35/m/meta-kernel.tn";

    /**
     * meta.tn closes {@code set<value>} itself; this schema imports meta.tn and closes it again. Both mint
     * the same derived name for the same body, so linking unifies them rather than reporting a collision.
     */
    @Test
    void anInstantiationAnImportAlreadyClosedUnifiesWithTheLocalOne() {
        assertDoesNotThrow(() -> Tson.builder().build().resolve("""
                !!id:"https://example.test/shared-instantiation.tn"
                !!meta:"%s"
                !!import:"%s"
                {
                  mine => set<value>
                }
                """.formatted(KERNEL, META)));
    }

    /** The same shape one layer down, through core.tn's own {@code set} template. */
    @Test
    void aConsumerClosingATemplateItsImportExportsLinks() {
        assertDoesNotThrow(() -> Tson.builder().build().resolve("""
                !!id:"https://example.test/shared-instantiation-core.tn"
                !!meta:"%s"
                !!import:"%s"
                {
                  tags => set<text>
                  box  => { a: tags }
                }
                """.formatted(META, CORE)));
    }

    /** A name an import already binds to a <em>different</em> type is still an error. */
    @Test
    void aLocalDeclarationShadowingAnImportedNameStillCollides() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> Tson.builder().build().resolve("""
                        !!id:"https://example.test/shadowing.tn"
                        !!meta:"%s"
                        !!import:"%s"
                        {
                          int32 => { a: text }
                        }
                        """.formatted(META, CORE)));

        assertTrue(thrown.getMessage().contains("'int32' collides"), thrown.getMessage());
    }
}
