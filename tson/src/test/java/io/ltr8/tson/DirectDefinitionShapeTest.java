package io.ltr8.tson;

import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>What a declaration whose body directly denotes a type resolves to</b> -- and how the two lift channels
 * still differ at that one position (§8.2).
 *
 * <p><b>Both are the declared entry now, rather than a hop to one.</b> §5.3's lift leaves {@code text_list =>
 * [text]} as the entry, and a declared application is its own instantiation. What still differs is what a
 * <em>use site</em> writing the same form reaches. An application <b>unifies</b>: a field typed {@code
 * box&lt;text&gt;} resolves to the declaration that named it, so §8.2's "two fully-bound applications denote
 * the same entry" holds within the schema. A sugar form <b>duplicates</b>: a use-site {@code [text]} mints its
 * own closed synthetic beside {@code text_list}, because §8.2 gives a declared entry its name as identity and
 * a minted one its content, and nothing makes those two meet.
 *
 * <p><b>So the asymmetry moved rather than closing.</b> It is no longer "a hop versus an entry" but "unifies
 * versus duplicates", and that is the half of #15 still open. This pins the shape, so a further change to it
 * is a deliberate edit here rather than a surprise somewhere downstream.
 */
class DirectDefinitionShapeTest {

    private static TsonLinkedSchema resolve(String id, String declarations) {
        return Tson.standard().resolve("""
                !!id:"https://example.test/%s.tn"
                !!meta:"%s"
                !!import:"%s"
                {
                %s}
                """.formatted(id, TsonBundledSchemas.META_ID, TsonBundledSchemas.CORE_ID, declarations));
    }

    /** Entry names this schema itself produced -- core.tn's own are the same under every one of these. */
    private static List<String> minted(TsonLinkedSchema linked, String prefix) {
        return linked.schema().entries().keySet().stream().filter(name -> name.startsWith(prefix)).toList();
    }

    // ── A sugar form at declaration position is the entry ────────────────

    @Test
    void aSugarFormAtDeclarationPositionIsTheEntryItself() {
        TypeDefinition textList = resolve("dd1", "  text_list => [text]\n").schema().entries().get("text_list");

        assertEquals(TypeKind.PRODUCT, textList.kind(), "the declaration is the array entry, not a hop to one");
        assertEquals("array", textList.source().orElseThrow().name());
    }

    /**
     * And a use site writing the same form mints its own, so the two coexist with identical content. That
     * duplicate is the one #15 argues is harmless here -- an array has no dispatch table over it to pollute.
     */
    @Test
    void aUseSiteWritingTheSameSugarFormMintsASeparateEntry() {
        TsonLinkedSchema linked = resolve("dd2", """
                  text_list => [text]
                  holder    => { f: [text] }
                """);

        assertEquals(TypeKind.PRODUCT, linked.schema().entries().get("text_list").kind());
        assertEquals(1, minted(linked, "array_").size(),
                () -> "the use site mints its own: " + minted(linked, "array_"));
    }

    // ── An application at declaration position is a reference ────────────

    /**
     * <b>Two names for one family member are two members, and §5.2 refuses them.</b> Each declaration is its
     * own entry (#15), so both pin {@code "dog"} and the family's mapping stops being a function -- the same
     * verdict two hand-written members with one pin already get. The duplication is not a naming or dedup
     * question at all: it is authored twice, and the pin rule is what catches it.
     */
    @Test
    void twoNamesForOneFamilyMemberCollideOnTheirPin() {
        SchemaValidationException thrown = assertThrows(SchemaValidationException.class, () -> resolve("dd5", """
                  dog_type => { breed: text }
                  pet      => <T, V> { type?: text = T  value: V }
                  dogs     => pet<"dog", dog_type>
                  hounds   => pet<"dog", dog_type>
                  holder   => { p: pet }
                """));

        assertTrue(thrown.getMessage().contains("to the same value"), thrown.getMessage());
    }
}
