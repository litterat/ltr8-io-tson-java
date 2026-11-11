package io.ltr8.tson;

import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.EntryKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>What a declaration whose body directly denotes a type resolves to</b> ([TSON-SCHEMA] §8.2): the declared entry,
 * whatever the form. {@code text_list => [text]} is the array entry under its name, and a declared application is
 * the record it closes to. A use site writing the same form holds the type in place, and is never looked up to the
 * declaration that names it.
 */
class DirectDefinitionShapeTest {

    private static TsonLinkedSchema resolve(String id, String declarations) {
        return Tson.standard().resolve("""
                !!id:"https://example.test/%s.tn"
                !!import:"%s"
                !!import:"%s"
                !schema {
                %s}
                """.formatted(id, TsonBundledSchemas.META_ID, TsonBundledSchemas.CORE_ID, declarations));
    }

    // ── A sugar form at declaration position is the entry ────────────────

    @Test
    void aSugarFormAtDeclarationPositionIsTheEntryItself() {
        var schema = resolve("dd1", "  text_list => [text]\n").schema();
        Top textList = schema.entries().get("text_list");

        assertEquals(EntryKind.PRODUCT, EntryKind.of(textList, n -> true),
                "the declaration is the array entry, not a hop to one");
        assertEquals(java.util.Optional.of("meta.array"), schema.constructorOf("text_list"));
    }

    // ── An application at declaration position is its own entry ──────────

    /**
     * <b>Two names for one family member are two members, and §5.2 refuses them.</b> Each declaration is its
     * own entry, so both pin {@code "dog"} and the family's mapping stops being a function -- the same
     * verdict two hand-written members with one pin already get. The duplication is authored twice, and the
     * pin rule is what catches it.
     */
    @Test
    void twoNamesForOneFamilyMemberCollideOnTheirPin() {
        SchemaValidationException thrown = assertThrows(SchemaValidationException.class, () -> resolve("dd5", """
                  dog_type => { breed: text }
                  pet      => abstract { type: text =? }
                  pet_of   => <V> pet & { value: V }
                  dogs     => pet_of<dog_type> & { type?: = "dog" }
                  hounds   => pet_of<dog_type> & { type?: = "dog" }
                  holder   => { p: pet }
                """));

        assertTrue(thrown.getMessage().contains("to the same value"), thrown.getMessage());
    }
}
