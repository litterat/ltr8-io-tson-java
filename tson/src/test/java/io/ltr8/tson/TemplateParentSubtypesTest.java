package io.ltr8.tson;

import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.TypeDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An instantiation indexes under the <b>parent entry</b> its template has ({@code SPEC-FEEDBACK.md} #13), so
 * a position typed by the template knows its members.
 *
 * <p><b>The head comes from {@code source}</b>, which §8.2 makes an instantiation record as written. That is
 * the whole of the derivation: no second index, and nothing to keep in step with the entries themselves.
 *
 * <p><b>The index is on the parent, not on the template.</b> A type position written {@code pet} resolves to
 * the closed entry the resolver minted for the base, so that is the entry every dispatcher consults -- and
 * leaving the members on the template would put them somewhere no position names. Each case here therefore
 * reaches the parent the way a reader does: through a field typed by the template.
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

    /** The parent entry, reached the way a reader reaches it: through a field typed by the template. */
    private static List<String> membersOf(TsonLinkedSchema schema, String holder, String field) {
        RecordBody body = assertInstanceOf(RecordBody.class, schema.schema().entries().get(holder).body());
        String typeName = body.fields().stream().filter(f -> f.name().equals(field)).findFirst()
                .orElseThrow(() -> new AssertionError("no field " + field)).type().name();
        TypeDefinition parent = schema.schema().entries().get(typeName);
        return parent == null ? List.of() : parent.subtypes();
    }

    private static List<String> subtypesOf(TsonLinkedSchema schema, String name) {
        TypeDefinition definition = schema.schema().entries().get(name);
        return definition == null ? List.of() : definition.subtypes();
    }

    @Test
    void anInstantiationIndexesUnderItsTemplatesParent() {
        TsonLinkedSchema schema = linked("s1", """
                  dog_type => { breed: text }
                  cat_type => { indoor: boolean }
                  pet      => <T, V> { type: text = T  value: V }
                  dogpet   => pet<"dog", dog_type>
                  catpet   => pet<"cat", cat_type>
                  holder   => { p: pet }
                """);

        List<String> members = membersOf(schema, "holder", "p");
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
                  holder   => { p: pet }
                """);

        assertTrue(membersOf(schema, "holder", "p").contains(target(schema, "dogpet")),
                () -> "the member indexes under the sealed parent: " + membersOf(schema, "holder", "p"));
    }

    /** Two arguments, two members, one index -- the parent is over every application, not per argument. */
    @Test
    void everyApplicationJoinsOneIndex() {
        TsonLinkedSchema schema = linked("s3", """
                  box     => <V> { item: V }
                  a_box   => box<text>
                  b_box   => box<int32>
                  holder  => { b: box }
                """);

        assertEquals(2, membersOf(schema, "holder", "b").size(),
                () -> membersOf(schema, "holder", "b").toString());
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
