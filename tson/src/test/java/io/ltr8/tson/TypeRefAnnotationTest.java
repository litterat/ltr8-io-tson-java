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
 * <p>§6 lets an annotation precede any value, and a {@code type_ref} at a field's type position is one:
 * {@code type: @doc:"..." token} annotates the <em>reference</em>, not the field around it, so {@link
 * TypeRef} is where it has to live. Before it had a carrier, a read discarded one and a write could not
 * produce one.
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

    /** An annotation written on the reference itself, read off a document. */
    @Test
    void anAnnotationOnATypeRefIsKept() {
        TypeRef type = fieldType(read("""
                !type_definition { kind: PRODUCT  body: !record { fields: [
                  !record_field { name: n  type: @doc:"the token itself" token }
                ] } }"""));

        assertEquals("token", type.name());
        assertEquals("the token itself", type.annotations().get("doc").orElseThrow().value().orElseThrow());
    }

    /** A reference with nothing written on it carries an empty carrier, not null. */
    @Test
    void anUnannotatedTypeRefCarriesNothing() {
        assertTrue(fieldType(read("""
                !type_definition { kind: PRODUCT  body: !record { fields: [
                  !record_field { name: n  type: token }
                ] } }""")).annotations().values().isEmpty());
    }

    /** And it writes back out, so a resolved-form round trip does not quietly drop it. */
    @Test
    void anAnnotatedTypeRefWritesBackAsOne() {
        TypeRef type = fieldType(read("""
                !type_definition { kind: PRODUCT  body: !record { fields: [
                  !record_field { name: n  type: @doc:"the token itself" token }
                ] } }"""));

        String written = new TsonObjectWriter(SchemaMetaNameBinder.defaultContext()).toTson(type);

        assertTrue(written.contains("@doc"), written);
        assertTrue(written.contains("the token itself"), written);
    }

    /**
     * An annotation does not change what a reference <em>is</em>. §8.2 keys entry identity on where a
     * reference points, and two use sites of one type differ only in what the author wrote around it, so
     * equality and hashing exclude the carrier — the same call {@code RecordField} already makes.
     */
    @Test
    void anAnnotationIsNotPartOfAReferencesIdentity() {
        TypeRef plain = TypeRef.of("token");
        TypeRef annotated = plain.withAnnotations(
                new io.ltr8.annotation.Annotations.Builder()
                        .add(new io.ltr8.annotation.Annotation("doc", java.util.Optional.of("a token")))
                        .build());

        assertEquals(plain, annotated);
        assertEquals(plain.hashCode(), annotated.hashCode());
        assertEquals("a token", annotated.annotations().get("doc").orElseThrow().value().orElseThrow());
    }
}
