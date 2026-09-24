package io.ltr8.tson;

import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>A declaration whose body is a fully-bound application is the instantiation entry</b>, not a reference to
 * a content-named one (§8.2): {@code bx => box<text>} resolves to {@code bx => !record
 * { … }}, and no {@code box_text_…} entry is minted beside it.
 *
 * <p><b>Why the hop was worth removing.</b> It made an author's name a second-class citizen of its own
 * declaration: the entry a document's {@code typeRef} reported, the entry {@code subtypes} indexed, and the
 * entry a diagnostic had to render through {@code EntryDisplayName} were all the derived one, and the name
 * the author wrote reached them only by being followed back. §8.2's own split says a declared entry's
 * identity is its name; this makes a declaration that denotes a type obey it, as a declaration that
 * constructs one already did (§5.3's lift leaves {@code text_list => [text]} as the entry).
 *
 * <p><b>One entry, not two.</b> A use site writing the same application resolves to the declaration rather
 * than minting a parallel entry, so §8.2's "two fully-bound applications denote the same entry" still holds
 * within the schema. Where two declarations name one application the first is the entry and the second is an
 * ordinary bare-name alias of it -- which composes and refines like any other reference (§4.3's chain walk).
 */
class DirectApplicationDeclarationTest {

    private static String schema(String id, String declarations) {
        return """
                !!id:"https://example.test/%s.tn"
                !!meta:"%s"
                !!import:"%s"
                {
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
    void aDeclaredApplicationIsTheInstantiationEntry() {
        TsonLinkedSchema linked = resolve("dad1", """
                  box => <V> { item: V }
                  bx  => box<text>
                """);

        TypeDefinition bx = linked.schema().entries().get("bx");
        assertEquals(TypeKind.PRODUCT, bx.kind(), "the declaration is the instantiation, not a hop to one");
        RecordBody body = assertInstanceOf(RecordBody.class, bx.body(), "its body is the closed record");
        assertEquals(List.of("item"), body.fields().stream().map(f -> f.name()).toList());
    }

    /** §8.2 keys an instantiation's identity on the application, which {@code source} records as written. */
    @Test
    void theDeclarationRecordsTheApplicationAsItsSource() {
        TypeDefinition bx = resolve("dad2", """
                  box => <V> { item: V }
                  bx  => box<text>
                """).schema().entries().get("bx");

        assertEquals("box", bx.source().orElseThrow().name());
        assertEquals(1, bx.source().orElseThrow().arguments().size(),
                () -> "the canonical application, arguments included: " + bx.source());
    }

    /** Nothing is minted beside it: an entry with no referent and no reader is not an entry. */
    @Test
    void noContentNamedEntryIsMintedBesideTheDeclaration() {
        TsonLinkedSchema linked = resolve("dad3", """
                  box => <V> { item: V }
                  bx  => box<text>
                """);

        assertEquals(List.of(), named(linked, "box_"),
                () -> "the declaration is the only entry for this application: " + linked.schema().entries().keySet());
    }

    /** A use site writing the same application reaches the declaration rather than a second entry. */
    @Test
    void aUseSiteWritingTheSameApplicationReachesTheDeclaration() {
        TsonLinkedSchema linked = resolve("dad4", """
                  box    => <V> { item: V }
                  bx     => box<text>
                  holder => { f: box<text> }
                """);

        assertEquals(List.of(), named(linked, "box_"),
                () -> "one entry for one application: " + linked.schema().entries().keySet());
        RecordBody holder = (RecordBody) linked.schema().entries().get("holder").body();
        assertEquals("bx", holder.fields().get(0).type().name(),
                "the field names the declaration, which is what the application denotes");
    }

    /**
     * <b>Two declarations of one application are two entries</b>, exactly as {@code a => { v: text }} and
     * {@code b => { v: text }} are two entries with one structure. Nothing dedupes them and nothing is
     * privileged: §8.2 gives each its name as its identity, and a rule that cares about the duplication --
     * §5.2's pin distinctness over a family's members -- catches it downstream on its own terms.
     */
    @Test
    void twoDeclarationsOfOneApplicationAreTwoEntries() {
        TsonLinkedSchema linked = resolve("dad5", """
                  box => <V> { item: V }
                  bx  => box<text>
                  by  => box<text>
                """);

        assertEquals(TypeKind.PRODUCT, linked.schema().entries().get("bx").kind());
        assertEquals(TypeKind.PRODUCT, linked.schema().entries().get("by").kind());
        assertEquals("box", linked.schema().entries().get("bx").source().orElseThrow().name());
        assertEquals("box", linked.schema().entries().get("by").source().orElseThrow().name());
        assertEquals(java.util.List.of("bx", "by"), linked.schema().entries().get("box").subtypes(),
                "both are members of the template they close");
    }

    // ── What it buys a family: the member is named, not hashed ───────────

    private static final String FAMILY = """
              dog_type => { breed: text }
              cat_type => { indoor: boolean }
              pet      => <V> { value: V }
              dogs     => pet<dog_type>
              cats     => pet<cat_type>
              holder   => { p: pet }
            """;

    /** The base indexes the names the author wrote, so a refusal can list names an author can write. */
    @Test
    void theFamilyBaseIndexesTheDeclaredMemberNames() {
        TsonLinkedSchema linked = resolve("dad6", FAMILY);

        assertEquals(List.of("dogs", "cats"), linked.schema().entries().get("pet").subtypes(),
                "the members are the declarations, not content-derived names");
    }

    /** And the tag a document writes is that name -- the whole point of the change. */
    @Test
    void anAbstractTemplateBaseDispatchesOnTheDeclaredName() {
        Tson tson = Tson.standard();
        tson.resolve(schema("dad7", FAMILY));

        TsonValue value = tson.treeReader().withSchema("https://example.test/dad7.tn")
                .readAs("{ p: !dogs { value: { breed: lab } } }", "holder");

        assertEquals("lab", value.at("/p/value/breed").asString().orElseThrow());
    }
}
