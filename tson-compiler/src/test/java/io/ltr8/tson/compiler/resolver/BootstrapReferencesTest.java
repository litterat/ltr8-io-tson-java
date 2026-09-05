package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TypeDefinition;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bootstrap route states references the way ordinary resolution does.
 *
 * <p>{@link MetaKernelBootstrapResolver} resolves meta-kernel by a shorter route -- two passes and no
 * materialisation, because {@code !!meta} naming itself leaves ordinary resolution nothing to bootstrap
 * from. Shorter, not different: this output governs every schema whose {@code !!meta} is meta-kernel, so a
 * use site the two routes disagreed about would be two answers to one question. Several tests drive this
 * route deliberately to exercise every step, which is exactly why it must not quietly diverge.
 *
 * <p>What that now means is that a use site names the entry the author wrote and nothing is attached to
 * record where it points -- [TSON-SCHEMA] §8.3's rewrite having been removed on both routes. The route used
 * to have a divergence available to it that the removal takes away: the bootstrap binds no name-position
 * annotations (it is producing the very entries a reader would bind them through), so anything carried from
 * a declaration to a use site could travel on one route and not the other. Nothing is carried now.
 */
class BootstrapReferencesTest {

    private static RecordField field(TypeDefinition definition, String name) {
        return assertInstanceOf(RecordBody.class, definition.body()).fields().stream()
                .filter(f -> f.name().equals(name)).findFirst().orElseThrow();
    }

    /** meta-kernel's own references, stated as written -- the fixture's own spelling. */
    @Test
    void aUseSiteNamesWhatWasWrittenOnTheBootstrapRouteToo() {
        TsonSchema kernel = MetaKernelBootstrapResolver.getMetaKernelSchema();

        RecordField name = field(kernel.entries().get("record_field"), "name");
        assertEquals("field_name", name.type().name());
        assertTrue(name.type().annotations().isEmpty(), "nothing records where it points");

        assertEquals("type_name", field(kernel.entries().get("type_ref"), "name").type().name());
    }

    /** And a position naming something that is not a reference is unaffected here as well. */
    @Test
    void aNonReferenceIsUnaffectedOnTheBootstrapRoute() {
        TypeDefinition recordField = MetaKernelBootstrapResolver.getMetaKernelSchema()
                .entries().get("record_field");

        assertEquals("type_ref", field(recordField, "type").type().name());
        assertTrue(field(recordField, "type").type().annotations().isEmpty());
    }

    /** The chain stays walkable through the entries, which is what a reader collapses it by. */
    @Test
    void aReferenceEntryKeepsItsHopOnTheBootstrapRoute() {
        TypeDefinition typeName = MetaKernelBootstrapResolver.getMetaKernelSchema().entries().get("type_name");

        assertEquals(io.ltr8.tson.schema.meta.TypeRef.of("identifier"),
                assertInstanceOf(io.ltr8.tson.schema.meta.Reference.class, typeName.body()).target());
    }
}
