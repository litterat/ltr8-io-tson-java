package io.ltr8.tson;

import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.EntryKind;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeChain;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * <b>A declaration whose body is a fully-bound application is the record it closes to</b> (§8.2): {@code bx =>
 * box<text>} resolves to {@code bx => !record { … }} under the author's name, its key recording the application
 * ({@code @meta.application}).
 *
 * <p>So the author's name is the entry a document's {@code typeRef} reports, the entry {@code subtypes} indexes and
 * the name a diagnostic renders. §8.2 makes a declared entry's identity its name, and a declaration that denotes a
 * type obeys it as one that constructs a type does ({@code text_list => [text]}).
 *
 * <p><b>One declaration, one entry.</b> A use site writing the same application closes in place and is never looked
 * up to the declaration (§5.10), and two declarations of one application are two entries.
 */
class DirectApplicationDeclarationTest {

    private static String schema(String id, String declarations) {
        return """
                !!id:"https://example.test/%s.tn"
                !!import:"%s"
                !!import:"%s"
                !schema {
                %s}
                """.formatted(id, TsonBundledSchemas.META_ID, TsonBundledSchemas.CORE_ID, declarations);
    }

    private static TsonLinkedSchema resolve(String id, String declarations) {
        return Tson.standard().resolve(schema(id, declarations));
    }

    private static List<String> named(TsonLinkedSchema linked, String prefix) {
        return linked.schema().entries().keySet().stream().filter(n -> n.startsWith(prefix)).toList();
    }

    // ── The declaration is the entry ─────────────────────────────────────

    @Test
    void aDeclaredApplicationIsTheRecordItClosesTo() {
        TsonLinkedSchema linked = resolve("dad1", """
                  box => <V> { item: V }
                  bx  => box<text>
                """);

        Top bx = linked.schema().entries().get("bx");
        assertEquals(EntryKind.PRODUCT, EntryKind.of(bx, n -> true),
                "the declaration is the instantiation, not a hop to one");
        RecordBody body = assertInstanceOf(RecordBody.class, bx, "its body is the closed record");
        assertEquals(List.of("item"), body.fields().keySet().stream().toList());
    }

    /** §8.2 keys an instantiation's identity on the application, which its facts record as written. */
    @Test
    void theDeclarationRecordsTheApplication() {
        var application = resolve("dad2", """
                  box => <V> { item: V }
                  bx  => box<text>
                """).schema().applicationOf("bx");

        assertEquals("box", application.orElseThrow().name());
        assertEquals(1, application.orElseThrow().arguments().size(),
                () -> "the canonical application, arguments included: " + application);
    }

    /**
     * A use site writing the same application is never looked up to the declaration (§5.10): it closes in place to a
     * record of the same shape, with no name, and adds no entry.
     */
    @Test
    void aUseSiteWritingTheSameApplicationClosesInPlace() {
        TsonLinkedSchema linked = resolve("dad4", """
                  box    => <V> { item: V }
                  bx     => box<text>
                  holder => { f: box<text> }
                """);

        assertEquals(List.of(), named(linked, "box_"),
                () -> "one entry for one declaration: " + linked.schema().entries().keySet());
        RecordBody holder = (RecordBody) linked.schema().entries().get("holder");
        io.ltr8.tson.schema.meta.TypeInline f = assertInstanceOf(io.ltr8.tson.schema.meta.TypeInline.class,
                holder.fields().get("f").type(), "the field holds the record in place, not the name 'bx'");
        assertEquals(linked.schema().entries().get("bx"), f.of(), "one shape");
    }

    /**
     * <b>Two declarations of one application are two entries</b>, exactly as {@code a => { v: text }} and
     * {@code b => { v: text }} are two entries with one structure. Neither is
     * privileged: §8.2 gives each its name as its identity, and a rule that cares about the duplication --
     * §5.2's pin distinctness over a family's members -- catches it downstream on its own terms. Neither is IS-A
     * the template (§5.10).
     */
    @Test
    void twoDeclarationsOfOneApplicationAreTwoEntries() {
        TsonLinkedSchema linked = resolve("dad5", """
                  box => <V> { item: V }
                  bx  => box<text>
                  by  => box<text>
                """);

        assertEquals(EntryKind.PRODUCT, EntryKind.of(linked.schema().entries().get("bx"), n -> true));
        assertEquals(EntryKind.PRODUCT, EntryKind.of(linked.schema().entries().get("by"), n -> true));
        assertEquals("box", linked.schema().applicationOf("bx").orElseThrow().name());
        assertEquals("box", linked.schema().applicationOf("by").orElseThrow().name());
        assertEquals(java.util.List.of(), TypeChain.subtypesOf(linked.schema().entries().get("box")),
                "a template is no type, and indexes neither");
    }

    // ── What it buys a family: the member is named by its declaration ───

    private static final String FAMILY = """
              dog_type => { breed: text }
              cat_type => { indoor: boolean }
              pet      => abstract { }
              pet_of   => <V> pet & { value: V }
              dogs     => pet_of<dog_type>
              cats     => pet_of<cat_type>
              holder   => { p: pet }
            """;

    /**
     * The base -- the record the template composes -- indexes the names the author wrote, so a refusal can list
     * names an author can write.
     */
    @Test
    void theFamilyBaseIndexesTheDeclaredMemberNames() {
        TsonLinkedSchema linked = resolve("dad6", FAMILY);

        assertEquals(List.of("dogs", "cats"), TypeChain.subtypesOf(linked.schema().entries().get("pet")),
                "the members are the declarations");
    }

    /** And the tag a document writes is that name. */
    @Test
    void anAbstractBaseDispatchesOnTheDeclaredName() {
        Tson tson = Tson.standard();
        tson.resolve(schema("dad7", FAMILY));

        TsonValue value = tson.treeReader().withSchema("https://example.test/dad7.tn")
                .readAs("{ p: !dogs { value: { breed: lab } } }", "holder");

        assertEquals("lab", value.at("/p/value/breed").asString().orElseThrow());
    }
}
