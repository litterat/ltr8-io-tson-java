package io.ltr8.tson;

import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * [TSON-SCHEMA] §8.3's use-site reference flattening.
 *
 * <p>A type position naming a {@code REFERENCE} entry is rewritten to the end of its chain, and the name
 * the author wrote survives on the reference as {@code @alias}. meta-kernel's own resolved fixture states
 * the rule — "Reference-kind names at type positions are flattened with @alias" — and spells the result
 * {@code type: @alias:field_name token}. What it buys is §8.2's single-level identity: a use site that
 * still named an alias would have to be chased before two of them could be compared.
 */
class ReferenceFlatteningTest {

    private static Tson tson() {
        return Tson.builder().dataBindContext(SchemaMetaNameBinder.defaultContext()).build();
    }

    private static TypeRef fieldType(TypeDefinition definition, String field) {
        return assertInstanceOf(RecordBody.class, definition.body()).fields().stream()
                .filter(f -> f.name().equals(field)).findFirst().orElseThrow().type();
    }

    private static Optional<String> alias(TypeRef type) {
        return type.annotations().get("alias").flatMap(a -> a.value()).map(String::valueOf);
    }

    /** The kernel's own case, and the fixture's: {@code field_name} aliases {@code token}. */
    @Test
    void aKernelAliasAtAFieldTypeIsFlattened() {
        TypeDefinition recordField = tson().bindRegistry().core()
                .resolveLinked(TsonBundledSchemas.META_KERNEL_ID).schema().entries().get("record_field");

        assertEquals("identifier", fieldType(recordField, "name").name());
        assertEquals(Optional.of("field_name"), alias(fieldType(recordField, "name")));

        TypeDefinition typeRef = tson().bindRegistry().core()
                .resolveLinked(TsonBundledSchemas.META_KERNEL_ID).schema().entries().get("type_ref");
        assertEquals("identifier", fieldType(typeRef, "name").name());
        assertEquals(Optional.of("type_name"), alias(fieldType(typeRef, "name")));
    }

    /** A position naming something that is not a reference is left exactly as it was. */
    @Test
    void aNonAliasIsUntouched() {
        TypeDefinition recordField = tson().bindRegistry().core()
                .resolveLinked(TsonBundledSchemas.META_KERNEL_ID).schema().entries().get("record_field");

        assertEquals("type_ref", fieldType(recordField, "type").name());
        assertEquals(Optional.empty(), alias(fieldType(recordField, "type")));
    }

    private static TypeDefinition resolve(String body, String entry) {
        String schema = """
                !!id:"https://example.test/flat.tn"
                !!meta:"https://tson.io/2026/34/m/meta.tn"
                !!import:"https://tson.io/2026/34/m/core.tn"
                { %s }
                """.formatted(body);
        return Tson.builder().schemaSource((TsonSchemaSource) uri -> schema)
                .dataBindContext(SchemaMetaNameBinder.defaultContext()).build()
                .resolve(schema).schema().entries().get(entry);
    }

    /** A chain of aliases collapses to the end of it, and the name written at the use site is the one kept. */
    @Test
    void aChainFlattensToItsTerminalAndKeepsTheNameWritten() {
        TypeDefinition holder = resolve("""
                  a => text
                  b => a
                  holder => { f: b }""", "holder");

        assertEquals("text", fieldType(holder, "f").name());
        assertEquals(Optional.of("b"), alias(fieldType(holder, "f")), "the alias written, not the one behind it");
    }

    /**
     * An alias entry keeps its own hop. The chain has to stay walkable for anything that wants the
     * intermediate names, and the entry is what records it -- flattening there would erase the alias
     * rather than relocate it.
     */
    @Test
    void anAliasEntryItselfIsNotFlattened() {
        TypeDefinition b = resolve("""
                  a => text
                  b => a
                  holder => { f: b }""", "b");

        assertEquals(TypeRef.of("a"),
                assertInstanceOf(io.ltr8.tson.schema.meta.Reference.class, b.body()).target());
    }

    /** Inside an argument list too, which is what makes a nested application's identity single-level. */
    @Test
    void anAliasNestedInAnArgumentIsFlattened() {
        TypeDefinition holder = resolve("""
                  a => text
                  holder => { f: [a] }""", "holder");

        TypeDefinition injected = resolve("""
                  a => text
                  holder => { f: [a] }""", fieldType(holder, "f").name());
        TypeRef element = assertInstanceOf(io.ltr8.tson.schema.meta.ArrayBody.class, injected.body()).elementType();

        assertEquals("text", element.name());
        assertEquals(Optional.of("a"), alias(element));
    }
}
