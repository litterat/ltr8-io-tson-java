package io.ltr8.tson;

import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.TypeDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An instantiation indexes under the parent its template has ({@code SPEC-FEEDBACK.md} #13), so a position
 * typed by the template knows its members.
 *
 * <p><b>The head comes from {@code source}</b>, which §8.2 makes an instantiation record as written. That is
 * the whole of the derivation: no second index, and nothing to keep in step with the entries themselves.
 *
 * <p><b>Only where the template has a parent.</b> A container, a constructor application and a reference
 * template are no types, so their applications index under nothing -- crediting them would put members under
 * something no position can name, which is the defect {@code SubtypeTemplateFamilyTest} removed from the
 * other direction when it stopped a template appearing in its own base's index.
 */
class TemplateParentSubtypesTest {

    private static final String HEADER = """
            !!id:"https://example.test/%s.tn"
            !!meta:"%s"
            !!import:"%s"
            """.formatted("%s", TsonBundledSchemas.META_ID, TsonBundledSchemas.CORE_ID);

    private static TsonLinkedSchema linked(String id, String declarations) {
        return Tson.standard().resolve(HEADER.formatted(id) + "{\n" + declarations + "}");
    }

    /** The entry a `name => head<args>` alias resolves to -- the instantiation materialisation minted. */
    private static String target(TsonLinkedSchema schema, String alias) {
        return schema.schema().entries().get(alias).source().orElseThrow().name();
    }

    private static List<String> subtypesOf(TsonLinkedSchema schema, String name) {
        TypeDefinition definition = schema.schema().entries().get(name);
        return definition == null ? List.of() : definition.subtypes();
    }

    @Test
    void anInstantiationIndexesUnderItsTemplate() {
        TsonLinkedSchema schema = linked("s1", """
                  dog_type => { breed: text }
                  cat_type => { indoor: boolean }
                  pet      => <T, V> { type: text = T  value: V }
                  dogpet   => pet<"dog", dog_type>
                  catpet   => pet<"cat", cat_type>
                """);

        List<String> members = subtypesOf(schema, "pet");
        assertTrue(members.contains(target(schema, "dogpet")), () -> "dogpet's entry is a member: " + members);
        assertTrue(members.contains(target(schema, "catpet")), () -> "catpet's entry is a member: " + members);
    }

    /** A labelled sum is the same, its parent being SEALED rather than ABSTRACT. */
    @Test
    void aSealedTemplateIndexesItsMembersToo() {
        TsonLinkedSchema schema = linked("s2", """
                  dog_type => { breed: text }
                  pet      => <T, V> { @discriminator type: text = T  value: V }
                  dogpet   => pet<"dog", dog_type>
                """);

        assertTrue(subtypesOf(schema, "pet").contains(target(schema, "dogpet")),
                () -> "the member indexes under the sealed parent: " + subtypesOf(schema, "pet"));
    }

    /** Two arguments, two members, one index -- the parent is over every application, not per argument. */
    @Test
    void everyApplicationJoinsOneIndex() {
        TsonLinkedSchema schema = linked("s3", """
                  box     => <V> { item: V }
                  a_box   => box<text>
                  b_box   => box<int32>
                """);

        assertEquals(2, subtypesOf(schema, "box").size(), () -> subtypesOf(schema, "box").toString());
    }

    // ── A template with no parent indexes nothing ────────────────────────

    @Test
    void aContainerTemplatesApplicationsIndexUnderNothing() {
        TsonLinkedSchema schema = linked("s4", """
                  arr   => <T> [T]
                  texts => arr<text>
                """);

        assertEquals(List.of(), subtypesOf(schema, "arr"));
    }

    @Test
    void aReferenceTemplatesApplicationsIndexUnderNothing() {
        TsonLinkedSchema schema = linked("s5", """
                  pair      => <A, B> { first: A  second: B }
                  uuid_pair => <B> pair<uuid, B>
                  named     => uuid_pair<text>
                """);

        assertEquals(List.of(), subtypesOf(schema, "uuid_pair"),
                "a reference is indirection, so it has no family of its own");
    }
}
