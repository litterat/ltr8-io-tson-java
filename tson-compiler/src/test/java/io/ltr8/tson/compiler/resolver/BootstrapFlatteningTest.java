package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The bootstrap route applies [TSON-SCHEMA] §8.3 like any other.
 *
 * <p>{@link MetaKernelBootstrapResolver} resolves meta-kernel by a shorter route -- two passes and no
 * materialisation, because {@code !!meta} naming itself leaves ordinary resolution nothing to bootstrap
 * from. Shorter, not different: this output is what governs every schema whose {@code !!meta} is
 * meta-kernel, so a use site flattened by ordinary resolution and left alone here would be two answers to
 * one question. Several tests drive this route deliberately to exercise every step, which is exactly why
 * it must not quietly diverge.
 */
class BootstrapFlatteningTest {

    private static RecordField field(TypeDefinition definition, String name) {
        return assertInstanceOf(RecordBody.class, definition.body()).fields().stream()
                .filter(f -> f.name().equals(name)).findFirst().orElseThrow();
    }

    private static Optional<String> alias(RecordField field) {
        return field.type().annotations().get("alias").flatMap(a -> a.value()).map(String::valueOf);
    }

    /** meta-kernel's own aliases, flattened, with the author's name kept -- the fixture's own spelling. */
    @Test
    void aUseSiteIsFlattenedOnTheBootstrapRouteToo() {
        TsonSchema kernel = MetaKernelBootstrapResolver.getMetaKernelSchema();
        TypeDefinition recordField = kernel.entries().get("record_field");

        assertEquals("token", field(recordField, "name").type().name());
        assertEquals(Optional.of("field_name"), alias(field(recordField, "name")));
        TypeDefinition typeRef = kernel.entries().get("type_ref");
        assertEquals("token", field(typeRef, "name").type().name());
        assertEquals(Optional.of("type_name"), alias(field(typeRef, "name")));
    }

    /** And what is not an alias is untouched here as well. */
    @Test
    void aNonAliasIsUntouchedOnTheBootstrapRoute() {
        TypeDefinition recordField = MetaKernelBootstrapResolver.getMetaKernelSchema()
                .entries().get("record_field");

        assertEquals("type_ref", field(recordField, "type").type().name());
        assertEquals(Optional.empty(), alias(field(recordField, "type")));
    }

    /** An alias entry keeps its own hop on this route too -- flattening moves a use site, not the record of it. */
    @Test
    void anAliasEntryKeepsItsHopOnTheBootstrapRoute() {
        TypeDefinition typeName = MetaKernelBootstrapResolver.getMetaKernelSchema().entries().get("type_name");

        assertEquals(io.ltr8.tson.schema.meta.TypeRef.of("token"),
                assertInstanceOf(io.ltr8.tson.schema.meta.Reference.class, typeName.body()).target());
    }
}
