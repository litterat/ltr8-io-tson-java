package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordExtensionType;
import io.ltr8.tson.schema.meta.RecordField;

import io.ltr8.tson.schema.meta.TypeChain;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>No template is a type</b> ([TSON-SCHEMA] §5.10): no position names one bare, an application of one is a type,
 * and an application's IS-A edges are its template's written parents', never the template's own name.
 *
 * <p>A family is declared with records: an abstract record states the selectors and the fields every member shares,
 * a template composes it, and each member composes an application and pins the selectors. {@code abstract} and a
 * selector on a template are each application's, so an abstract template's application is itself a family base,
 * over the declarations composing it.
 */
class TemplateIsNotATypeTest {

    private static final String HEADER = """
            !!id:"https://example.test/%s.tn"
            !!import:"%s"
            !!import:"%s"
            !schema
            """.formatted("%s", TsonBundledSchemas.META_ID, TsonBundledSchemas.CORE_ID);

    private static TsonLinkedSchema linked(String id, String declarations) {
        return Tson.standard().resolve(HEADER.formatted(id) + "{\n" + declarations + "}");
    }

    private static List<Diagnostic> load(String id, String declarations) {
        return Tson.standard().validateSchema(HEADER.formatted(id) + "{\n" + declarations + "}");
    }

    private static List<Diagnostic> read(String id, String declarations, String document) {
        Tson tson = Tson.standard();
        tson.resolve(HEADER.formatted(id) + "{\n" + declarations + "}");
        List<Diagnostic> problems = new ArrayList<>();
        tson.treeReader().withDiagnostics(problems::add)
                .withSchema("https://example.test/" + id + ".tn").readAs(document, "holder");
        return problems;
    }

    private static boolean refusedAsATemplate(List<Diagnostic> problems, String template) {
        return problems.stream().anyMatch(d -> d.message().contains("'" + template + "' is a template taking")
                && d.message().contains("a template is not a type"));
    }

    /** The family §5.10 declares with records: the base states the selector, the template composes it. */
    private static final String RECORD_FAMILY = """
              dog_type => { breed: text }
              cat_type => { indoor: boolean }
              pet      => abstract { kind: text =?  name: text }
              pet_of   => <T> pet & { pet: T }
              dog      => pet_of<dog_type> & { kind?: = "dog" }
              cat      => pet_of<cat_type> & { kind?: = "cat" }
              holder   => { p: pet }
            """;

    /** The family whose base is an abstract template's application. */
    private static final String APPLIED_FAMILY = """
              tpet   => abstract <B> { kind: text =?  name: text  extra?: B }
              tdog   => tpet<text> & { kind?: = "dog"  breed: text }
              tcat   => tpet<text> & { kind?: = "cat"  indoor: boolean }
              holder => { p: tpet<text> }
            """;

    // ── No position names a template bare ─────────────────────────────────

    /** A record template is refused bare at a position as a held one is, a selector notwithstanding. */
    @Disabled("Phase 2: an undeclared application of an open record closes in place")
    @Test
    void aTemplateNamedBareAtAPositionIsRefused() {
        for (String holder : List.of("holder => { p: pet }", "holder => { ps: [pet] }",
                "holder => ( pet | text )", "holder => { m: { text => pet } }", "holder => { t: [pet text] }",
                "wrap => <W> { w: W }\n  holder => { x: wrap<pet> }")) {
            List<Diagnostic> problems = load("b-" + Math.abs(holder.hashCode()), """
                      pet    => abstract <V> { kind: text =?  value: V }
                      dogpet => pet<text> & { kind?: = "dog" }
                    """ + "  " + holder + "\n");
            assertTrue(refusedAsATemplate(problems, "pet"), () -> holder + ": " + problems);
        }
    }

    /** A template named bare inside a template's own body is refused at the template's declaration. */
    @Test
    void aTemplateNamedBareInsideATemplateIsRefused() {
        assertTrue(refusedAsATemplate(load("b-inner", """
                  chain => <T> { head: T  tail?: chain? }
                """), "chain"));
    }

    // ── A family declared with records ────────────────────────────────────

    /** A member reaches the base through its template's written parent, and is selected by its pin. */
    @Test
    void aMemberComposingAnApplicationIsSelectedByItsPin() {
        assertEquals(List.of(), read("r1", RECORD_FAMILY,
                "!holder { p: { kind: dog  name: Rex  pet: { breed: lab } } }"));
        assertEquals(List.of(), read("r2", RECORD_FAMILY,
                "!holder { p: { kind: cat  name: Tom  pet: { indoor: true } } }"));
    }

    /** A pin no member states is refused, offering the pins a document could write. */
    @Test
    void anUnmatchedPinIsRefusedOfferingTheMembersPins() {
        List<Diagnostic> problems = read("r3", RECORD_FAMILY,
                "!holder { p: { kind: fish  name: x  pet: { breed: x } } }");

        assertEquals(1, problems.size(), problems::toString);
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, problems.getFirst().code());
        assertTrue(problems.getFirst().message().contains("fish"), problems.getFirst()::message);
        assertTrue(problems.getFirst().message().contains("dog") && problems.getFirst().message().contains("cat"),
                problems.getFirst()::message);
    }

    /** The base indexes the members; the template is in no index and IS-A-ed by nothing. */
    @Test
    void theBaseIndexesTheMembersAndNotTheTemplate() {
        TsonLinkedSchema schema = linked("r4", RECORD_FAMILY);

        assertEquals(List.of("cat", "dog"),
                TypeChain.subtypesOf(schema.schema().entries().get("pet")).stream().sorted().toList());
        assertEquals(List.of(), TypeChain.subtypesOf(schema.schema().entries().get("pet_of")));
        assertEquals(List.of("pet"), TypeChain.supertypesOf(schema.schema().entries().get("dog")));
    }

    /**
     * A declared application is no member of its template and is IS-A its template's written parent: {@code bt}
     * reaches {@code boxed} through {@code box}, and a position typed {@code boxed} dispatches by tag.
     */
    @Test
    void aDeclaredApplicationIsIsAItsTemplatesParentAndNotItsTemplate() {
        String declarations = """
                  boxed  => abstract { }
                  box    => <T> boxed & { v: T }
                  bt     => box<int32>
                  holder => { any: boxed }
                """;
        TsonLinkedSchema schema = linked("r5", declarations);

        assertEquals(List.of("boxed"), TypeChain.supertypesOf(schema.schema().entries().get("bt")));
        assertEquals(List.of(), TypeChain.subtypesOf(schema.schema().entries().get("box")));
        assertEquals(List.of(), read("r5", declarations, "!holder { any: !bt { v: 1 } }"));
        List<Diagnostic> untagged = read("r5", declarations, "!holder { any: { v: 1 } }");
        assertEquals(1, untagged.size(), untagged::toString);
        assertTrue(untagged.getFirst().message().contains("one of (bt)"), untagged::toString);
    }

    /** No application indexes under its template, whatever the template's body. */
    @Test
    void noApplicationIndexesUnderItsTemplate() {
        TsonLinkedSchema schema = linked("r7", """
                  box    => <V> { item: V }
                  a_box  => box<text>
                  b_box  => box<int32>
                  pet    => <V> { type: text =?  value: V }
                  dogpet => pet<text> & { type?: = "dog" }
                  arr    => <T> { items: [T] }
                  texts  => arr<text>
                """);

        for (String template : List.of("box", "pet", "arr")) {
            assertEquals(List.of(), TypeChain.subtypesOf(schema.schema().entries().get(template)), template);
        }
        assertEquals(List.of(), TypeChain.supertypesOf(schema.schema().entries().get("a_box")));
    }

    /** A use-site application whose template composes a declared base would be an unnameable member: refused. */
    @Disabled("Phase 2: an undeclared application of an open record closes in place")
    @Test
    void aUseSiteApplicationComposingADeclaredBaseIsRefused() {
        List<Diagnostic> problems = load("r6", """
                  boxed  => abstract { }
                  box    => <T> boxed & { v: T }
                  holder => { k: box<text> }
                """);
        assertTrue(problems.stream().anyMatch(d -> d.message().contains("composes onto 'boxed'")),
                problems::toString);
    }

    // ── An abstract template's application is a family base ───────────────

    /** {@code abstract} and the selector are each application's: {@code tpet<text>} dispatches on {@code kind}. */
    @Disabled("Phase 2: an undeclared application of an open record closes in place")
    @Test
    void anAbstractTemplatesApplicationDispatchesOverItsMembers() {
        assertEquals(List.of(), read("a1", APPLIED_FAMILY, "!holder { p: { kind: cat  name: Tom  indoor: true } }"));
        assertEquals(List.of(), read("a2", APPLIED_FAMILY, "!holder { p: { kind: dog  name: Rex  breed: lab } }"));
        List<Diagnostic> problems = read("a3", APPLIED_FAMILY, "!holder { p: { kind: fish  name: x } }");
        assertEquals(1, problems.size(), problems::toString);
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, problems.getFirst().code());
    }

    /** The application closes to an abstract record carrying the template's selector; the template is no base. */
    @Disabled("Phase 2: an undeclared application of an open record closes in place")
    @Test
    void theApplicationCarriesTheTemplatesExtensionAndSelector() {
        TsonLinkedSchema schema = linked("a4", APPLIED_FAMILY + "  tpet_text => tpet<text>\n");

        RecordBody base = (RecordBody) schema.schema().entries().get("tpet_text");
        assertEquals(RecordExtensionType.ABSTRACT, base.extension());
        assertEquals(List.of("kind"), base.discriminators());
        assertEquals(List.of("tcat", "tdog"), TypeChain.subtypesOf(base).stream().sorted().toList());
        RecordBody template = (RecordBody) schema.schema().entries().get("tpet");
        assertEquals(List.of(), TypeChain.subtypesOf(template));
        assertFalse(TypeChain.supertypesOf(schema.schema().entries().get("tdog")).contains("tpet"));
    }

    /** A selector template applied with no member composing it is a base with nothing to select. */
    @Disabled("Phase 2: an undeclared application of an open record closes in place")
    @Test
    void aSelectorTemplatesApplicationWithNoMemberSelectsNothing() {
        List<Diagnostic> problems = read("a5", """
                  pet    => <V> { kind: text =?  name: V }
                  holder => { p: pet<text> }
                """, "!holder { p: { kind: cat  name: x } }");

        assertEquals(1, problems.size(), problems::toString);
        assertEquals("(no cat, and none to name)", problems.getFirst().actual());
    }

    /** {@code final} stays refused on a template. */
    @Test
    void finalIsRefusedOnATemplate() {
        assertTrue(load("a6", "  pet => final <T> { value: T }\n").stream()
                .anyMatch(d -> d.message().contains("'final' is refused on a template")));
    }

    // ── A field naming no parameter names one entry in every application ────

    private static String fieldType(TsonLinkedSchema schema, String entry, String field) {
        return switch (schema.schema().entries().get(entry)) {
            case RecordBody record -> record.fields().get(field).typeExpr().text();
            default -> throw new AssertionError(entry + " is no record");
        };
    }

    /** A field type naming none of the template's parameters is closed in the template, to the entry each names. */
    @Disabled("Phase 2: an undeclared application of an open record closes in place")
    @Test
    void aClosedApplicationInATemplateIsTheEntryEveryApplicationNames() {
        TsonLinkedSchema schema = linked("c1", """
                  box  => <T> { v: T }
                  pet  => <V> { tag: box<text>  tags: [box<text>]  name: V }
                  cat  => pet<text>
                """);

        assertEquals(fieldType(schema, "cat", "tag"), fieldType(schema, "pet", "tag"));
        String tags = fieldType(schema, "pet", "tags");
        assertEquals(fieldType(schema, "cat", "tags"), tags);
        assertTrue(schema.schema().entries().containsKey(tags), () -> tags + " is not an entry of the schema");
    }
}
