package io.ltr8.tson;

import io.ltr8.tson.compiler.TsonObjectWriter;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeRef;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code type_ref} keeps the wire annotations written on the reference itself.
 *
 * <p>[TSON-SCHEMA] §8.3 attaches {@code @alias:name} to the <em>type value</em> when a use site is
 * flattened past a {@code REFERENCE} entry — {@code type: @alias:field_name token} says both where the
 * reference now points and what the author wrote. The annotation belongs to the reference, not to the
 * field around it, so {@link TypeRef} is where it has to live; before it had a carrier, a read discarded
 * one and a write could not produce one.
 */
class TypeRefAnnotationTest {

    private static TypeDefinition read(String document) {
        return Tson.builder().dataBindContext(SchemaMetaNameBinder.defaultContext()).build()
                .objectReader().withSchema(TsonBundledSchemas.META_ID)
                .readAs(document, "type_definition", TypeDefinition.class);
    }

    private static TypeRef fieldType(TypeDefinition definition) {
        return assertInstanceOf(RecordBody.class, definition.body()).fields().getFirst().type();
    }

    /** §8.3's own shape, read off a document. */
    @Test
    void anAliasOnATypeRefIsKept() {
        TypeRef type = fieldType(read("""
                !type_definition { kind: PRODUCT  body: !record { fields: [
                  !record_field { name: n  type: @alias:field_name token }
                ] } }"""));

        assertEquals("token", type.name());
        assertEquals("field_name", type.annotations().get("alias").orElseThrow().value().orElseThrow());
    }

    /** A reference with nothing written on it carries an empty carrier, not null. */
    @Test
    void anUnannotatedTypeRefCarriesNothing() {
        assertTrue(fieldType(read("""
                !type_definition { kind: PRODUCT  body: !record { fields: [
                  !record_field { name: n  type: token }
                ] } }""")).annotations().values().isEmpty());
    }

    /** And it writes back out, which is the half §8.3 needs to emit a flattened use site. */
    @Test
    void anAliasedTypeRefWritesBackAsOne() {
        TypeRef type = fieldType(read("""
                !type_definition { kind: PRODUCT  body: !record { fields: [
                  !record_field { name: n  type: @alias:field_name token }
                ] } }"""));

        String written = new TsonObjectWriter(SchemaMetaNameBinder.defaultContext()).toTson(type);

        assertTrue(written.contains("@alias"), written);
        assertTrue(written.contains("field_name"), written);
    }

    /**
     * An alias does not change what a reference <em>is</em>. §8.2 keys entry identity on where a reference
     * points, and two use sites of one type differ only in what the author happened to write, so equality
     * and hashing exclude the carrier — the same call {@code RecordField} already makes.
     */
    @Test
    void anAliasIsNotPartOfAReferencesIdentity() {
        TypeRef plain = TypeRef.of("token");
        TypeRef aliased = plain.withAnnotations(
                new io.ltr8.annotation.Annotations.Builder()
                        .add(new io.ltr8.annotation.Annotation("alias", java.util.Optional.of("field_name")))
                        .build());

        assertEquals(plain, aliased);
        assertEquals(plain.hashCode(), aliased.hashCode());
        assertEquals("field_name", aliased.annotations().get("alias").orElseThrow().value().orElseThrow());
    }
}
