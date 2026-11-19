package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.TypeExpr;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordExtensionType;

import io.ltr8.tson.schema.meta.TypeChain;
import io.ltr8.tson.schema.meta.TypeInline;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>No parametric record is a type</b> ([TSON-SCHEMA] §5.10): no position names one bare, an application of one is a type,
 * and an application's IS-A edges are its head's written parents', never its head's own name.
 *
 * <p>A family is declared with records: an abstract record states the selectors and the fields every member shares,
 * a parametric record composes it, and each member composes an application and pins the selectors. {@code abstract} and a
 * selector on a parametric record are each application's, so an abstract parametric record's application is itself a family
 * base, over the declarations composing it.
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
        return problems.stream().anyMatch(d -> d.message().contains("'" + template + "'")
                && d.message().contains("is a parametric record")
                && d.message().contains("a parametric record is not a type"));
    }

    /** The family §5.10 declares with records: the base states the selector, the parametric record composes it. */
    private static final String RECORD_FAMILY = """
              dog_type => { breed: text }
              cat_type => { indoor: boolean }
              pet      => abstract { kind: text =?  name: text }
              pet_of   => <T> pet & { pet: T }
              dog      => pet_of<dog_type> & { kind?: = "dog" }
              cat      => pet_of<cat_type> & { kind?: = "cat" }
              holder   => { p: pet }
            """;

    /**
     * The family whose base is an abstract parametric record's declared application: a member reaches it through an applied
     * parent's edge, which names the entry a declaration gives the application (§5.10).
     */
    private static final String APPLIED_FAMILY = """
              tpet      => abstract <B> { kind: text =?  name: text  extra?: B }
              tpet_text => tpet<text>
              tdog      => tpet<text> & { kind?: = "dog"  breed: text }
              tcat      => tpet<text> & { kind?: = "cat"  indoor: boolean }
              holder    => { p: tpet_text }
            """;

    // ── No position names a parametric record bare ─────────────────────────────────

    /** A parametric record is refused bare at a position as a held one is, a selector notwithstanding. */
    @Test
    void aParametricRecordNamedBareAtAPositionIsRefused() {
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

    /**
     * A parametric record named bare inside a parametric record's own body is refused at the parametric record's
     * declaration.
     */
    @Test
    void aParametricRecordNamedBareInsideAParametricRecordIsRefused() {
        assertTrue(refusedAsATemplate(load("b-inner", """
                  chain => <T> { head: T  tail?: chain? }
                """), "chain"));
    }

    // ── A family declared with records ────────────────────────────────────

    /** A member reaches the base through its parametric record's written parent, and is selected by its pin. */
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

    /** The base indexes the members; the parametric record is in no index and IS-A-ed by nothing. */
    @Test
    void theBaseIndexesTheMembersAndNotTheParametricRecord() {
        TsonLinkedSchema schema = linked("r4", RECORD_FAMILY);

        assertEquals(List.of("cat", "dog"),
                TypeChain.subtypesOf(schema.schema().entries().get("pet")).stream().sorted().toList());
        assertEquals(List.of(), TypeChain.subtypesOf(schema.schema().entries().get("pet_of")));
        assertEquals(List.of("pet"), TypeChain.supertypesOf(schema.schema().entries().get("dog")));
    }

    /**
     * A declared application is no member of its head and is IS-A its head's written parent: {@code bt}
     * reaches {@code boxed} through {@code box}, and a position typed {@code boxed} dispatches by tag.
     */
    @Test
    void aDeclaredApplicationIsIsAItsHeadsParentAndNotItsHead() {
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

    /** No application indexes under its head, whatever the head's body. */
    @Test
    void noApplicationIndexesUnderItsHead() {
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

    /** A use-site application whose head composes a declared base would be an unnameable member: refused. */
    @Test
    void aUseSiteApplicationComposingADeclaredBaseIsRefused() {
        List<Diagnostic> problems = load("r6", """
                  boxed  => abstract { }
                  box    => <T> boxed & { v: T }
                  holder => { k: box<text> }
                """);
        assertTrue(problems.stream().anyMatch(d -> d.message().contains("would close in place to a record IS-A "
                + "'boxed'")), problems::toString);
    }

    // ── An abstract parametric record's application is a family base ───────────────

    /**
     * {@code abstract} and the selector are each application's: {@code tpet_text => tpet<text>} dispatches on {@code
     * kind} over the declarations composing {@code tpet<text>}, and the same application closed in place has no member
     * (§5.10).
     */
    @Test
    void anAbstractParametricRecordsApplicationDispatchesOverItsMembers() {
        assertEquals(List.of(), read("a1", APPLIED_FAMILY, "!holder { p: { kind: cat  name: Tom  indoor: true } }"));
        assertEquals(List.of(), read("a2", APPLIED_FAMILY, "!holder { p: { kind: dog  name: Rex  breed: lab } }"));
        List<Diagnostic> problems = read("a3", APPLIED_FAMILY, "!holder { p: { kind: fish  name: x } }");
        assertEquals(1, problems.size(), problems::toString);
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, problems.getFirst().code());
        List<Diagnostic> inPlace = read("a3p", APPLIED_FAMILY.replace("{ p: tpet_text }", "{ p?: tpet<text> }"),
                "!holder { p: { kind: dog  name: Rex  breed: lab } }");
        assertEquals(1, inPlace.size(), inPlace::toString);
        assertEquals(Optional.of("/p"), inPlace.getFirst().path(), inPlace::toString);
    }

    /**
     * The application closes to an abstract record carrying the parametric record's selector; the parametric record is no
     * base.
     */
    @Test
    void theApplicationCarriesTheParametricRecordsExtensionAndSelector() {
        TsonLinkedSchema schema = linked("a4", APPLIED_FAMILY);

        RecordBody base = (RecordBody) schema.schema().entries().get("tpet_text");
        assertEquals(RecordExtensionType.ABSTRACT, base.extension());
        assertEquals(List.of("kind"), base.discriminators());
        assertEquals(List.of("tcat", "tdog"), TypeChain.subtypesOf(base).stream().sorted().toList());
        RecordBody template = (RecordBody) schema.schema().entries().get("tpet");
        assertEquals(List.of(), TypeChain.subtypesOf(template));
        assertFalse(TypeChain.supertypesOf(schema.schema().entries().get("tdog")).contains("tpet"));
    }

    /** A parametric record with a selector, applied with no member composing it, is a base with nothing to select. */
    @Test
    void anApplicationOfAParametricRecordWithASelectorAndNoMemberSelectsNothing() {
        List<Diagnostic> problems = read("a5", """
                  pet    => <V> { kind: text =?  name: V }
                  holder => { p?: pet<text> }
                """, "!holder { p: { kind: cat  name: x } }");

        assertEquals(1, problems.size(), problems::toString);
        assertEquals("(no cat, and none to name)", problems.getFirst().actual());
    }

    /** {@code final} stays refused on a parametric record. */
    @Test
    void finalIsRefusedOnAParametricRecord() {
        assertTrue(load("a6", "  pet => final <T> { value: T }\n").stream()
                .anyMatch(d -> d.message().contains("'final' is refused on a parametric record")));
    }

    // ── A field naming no parameter is one type in every application ───────

    private static TypeExpr fieldType(TsonLinkedSchema schema, String entry, String field) {
        return switch (schema.schema().entries().get(entry)) {
            case RecordBody record -> record.fields().get(field).type();
            default -> throw new AssertionError(entry + " is no record");
        };
    }

    /**
     * A field type naming none of the parametric record's parameters is closed in the parametric record, in place, and every
     * application holds the same type: nothing is added to the schema for it.
     */
    @Test
    void aClosedApplicationInAParametricRecordIsOneTypeInEveryApplication() {
        TsonLinkedSchema schema = linked("c1", """
                  box  => <T> { v: T }
                  pet  => <V> { tag: box<text>  tags: [box<text>]  name: V }
                  cat  => pet<text>
                """);

        assertInstanceOf(TypeInline.class, fieldType(schema, "pet", "tag"));
        assertEquals(fieldType(schema, "pet", "tag"), fieldType(schema, "cat", "tag"));
        assertInstanceOf(TypeInline.class, fieldType(schema, "pet", "tags"));
        assertEquals(fieldType(schema, "pet", "tags"), fieldType(schema, "cat", "tags"));
    }
}
