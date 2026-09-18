package io.ltr8.tson;

import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>What a declaration whose body directly denotes a type resolves to</b> -- and the two lift channels give
 * opposite answers at that one position ({@code SPEC-FEEDBACK.md} #15).
 *
 * <p>A <b>sugar form</b> written at declaration position becomes the declared entry ([TSON-SCHEMA] §5.3), and
 * duplicates freely with a use site that writes the same form. An <b>application</b> written at the same
 * position becomes a {@code REFERENCE} to a content-named instantiation entry, and unifies with every use site
 * that writes it (§8.2).
 *
 * <p><b>This test pins the asymmetry rather than endorsing it.</b> §8.2's own split -- a declared entry's
 * identity is its name, a minted entry's is its canonical content -- is what the reference hop buys: a schema
 * writing {@code box&lt;text&gt;} that has never seen the schema declaring {@code a} reaches that same entry,
 * so an import merge unifies rather than collides (§3.3.4), which an author's name cannot do. #15 is the open
 * question of whether the positions should differ at all; until it closes, this is the shape, and a change to
 * it should be a deliberate edit here rather than a surprise somewhere downstream.
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

    @Test
    void anApplicationAtDeclarationPositionIsAReferenceToAMintedEntry() {
        TsonLinkedSchema linked = resolve("dd3", """
                  box => <V> { item: V }
                  a   => box<text>
                """);

        TypeDefinition a = linked.schema().entries().get("a");
        assertEquals(TypeKind.REFERENCE, a.kind(), "the declaration is a hop, not the instantiation itself");
        assertTrue(a.source().orElseThrow().name().startsWith("box_"),
                () -> "its target is the content-named instantiation: " + a.source().orElseThrow().name());
        assertNotEquals("a", a.source().orElseThrow().name());
    }

    /** Two declarations naming one application are two aliases over one entry, neither privileged (§8.2). */
    @Test
    void twoDeclarationsNamingOneApplicationShareOneEntry() {
        TsonLinkedSchema linked = resolve("dd4", """
                  box => <V> { item: V }
                  a   => box<text>
                  b   => box<text>
                """);

        String fromA = linked.schema().entries().get("a").source().orElseThrow().name();
        String fromB = linked.schema().entries().get("b").source().orElseThrow().name();

        assertEquals(fromA, fromB, "both aliases resolve to the same instantiation");
        assertEquals(1, minted(linked, "box_").size(),
                () -> "and only one entry is minted for it: " + minted(linked, "box_"));
    }

    /**
     * <b>The family consequence, which is where #15's "duplicates are inert" is load-bearing.</b> Two names
     * for one member collapse to one entry, so the base indexes one member and §5.2's pin-distinctness rule
     * has nothing to refuse. Were they two entries, both would pin {@code "dog"} and the rule would refuse a
     * schema that loads today -- which is why #15 pairs its proposal with a relaxation of that rule.
     */
    @Test
    void twoNamesForOneFamilyMemberCollapseSoThePinRuleHasNothingToRefuse() {
        TsonLinkedSchema linked = resolve("dd5", """
                  dog_type => { breed: text }
                  pet      => <T, V> { @discriminator type: text = T  value: V }
                  dogs     => pet<"dog", dog_type>
                  hounds   => pet<"dog", dog_type>
                  holder   => { p: pet }
                """);

        assertEquals(linked.schema().entries().get("dogs").source().orElseThrow().name(),
                linked.schema().entries().get("hounds").source().orElseThrow().name());
        assertEquals(1, linked.schema().entries().get("pet").subtypes().size(),
                () -> "one member, not two: " + linked.schema().entries().get("pet").subtypes());
    }
}
