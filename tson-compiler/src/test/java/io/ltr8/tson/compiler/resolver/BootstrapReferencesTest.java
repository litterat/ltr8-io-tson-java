package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.Alias;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bootstrap route states references the way ordinary resolution does.
 *
 * <p>{@link KernelBootstrapResolver} resolves the kernel by a shorter route -- two passes and no
 * materialisation, because the kernel declares every constructor it applies, which leaves ordinary resolution
 * nothing to bootstrap from. Shorter, not different: every schema importing the kernel reads through this output, so
 * a use site the two routes disagreed about would be two answers to one question. Several tests drive this
 * route deliberately to exercise every step, which is exactly why it must not quietly diverge.
 *
 * <p>What that now means is that a use site names the entry the author wrote and nothing is attached to
 * record where it points -- [TSON-SCHEMA] §8.3's rewrite having been removed on both routes. The route used
 * to have a divergence available to it that the removal takes away: the bootstrap binds no name-position
 * annotations (it is producing the very entries a reader would bind them through), so anything carried from
 * a declaration to a use site could travel on one route and not the other. Nothing is carried now.
 */
class BootstrapReferencesTest {

    private static RecordField field(Top definition, String name) {
        return assertInstanceOf(RecordBody.class, definition).fields().entrySet().stream()
                .filter(f -> f.getKey().equals(name)).map(java.util.Map.Entry::getValue).findFirst().orElseThrow();
    }

    /** The kernel's own references, stated as written -- the fixture's own spelling. */
    @Test
    void aUseSiteNamesWhatWasWrittenOnTheBootstrapRouteToo() {
        TsonSchema kernel = KernelBootstrapResolver.kernelSchema();

        RecordField name = field(kernel.entries().get("meta.param"), "name");
        assertEquals("meta.param_name", name.typeExpr().text());
        assertTrue(((io.ltr8.tson.schema.meta.TypeRef) name.type()).annotations().isEmpty(),
                "nothing records where it points");
    }

    /** And a position naming something that is not an alias is unaffected here as well. */
    @Test
    void aNonAliasIsUnaffectedOnTheBootstrapRoute() {
        Top recordField = KernelBootstrapResolver.kernelSchema()
                .entries().get("meta.record_field");

        assertEquals("meta.type_expr", field(recordField, "type").typeExpr().text());
        assertTrue(((io.ltr8.tson.schema.meta.TypeRef) field(recordField, "type").type()).annotations().isEmpty());
    }

    /** The chain stays walkable through the entries, which is what a reader collapses it by. */
    @Test
    void anAliasEntryKeepsItsHopOnTheBootstrapRoute() {
        Top typeName = KernelBootstrapResolver.kernelSchema().entries().get("meta.type_name");

        assertEquals(io.ltr8.tson.schema.meta.TypeRef.of("meta.identifier"),
                assertInstanceOf(Alias.class, typeName).target());
    }
}
