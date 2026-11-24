package io.ltr8.tson.compiler;

import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.RecordBody;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [TSON-SCHEMA] §5.8's rule for a restated field's type: the restated type is the inherited one or a type IS-A it
 * (§7.2), or {@code void} at a voidable position. Judged at link, where every name both types hold resolves.
 */
class RestatedFieldTypeTest {

    private static final String ID = "https://example.test/restated.tn";

    private static TsonLinkedSchema link(String body) {
        String schema = """
                !!id:"https://example.test/restated.tn"
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !!import:"https://tson.io/2026/40/m/core.tn"
                !schema
                """ + body;
        return TsonCompiledVocabularyRegistry.withStandardLibrary(SchemaModelNameBinder.defaultContext(), uri -> {
            if (CanonicalIdentity.sameIdentity(uri, ID)) {
                return schema;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        }).resolveLinked(ID);
    }

    private static String refused(String body) {
        return assertThrows(SchemaValidationException.class, () -> link(body)).getMessage();
    }

    @Test
    void aFieldRestatedAsAnUnrelatedTypeIsRefused() {
        String message = refused("""
                {
                  a => { x: text }
                  b => a & { x: int32 }
                }
                """);

        assertTrue(message.contains("'b': not a valid restatement of 'a''s fields (§5.8): field 'x' is typed "
                + "'int32', which is not 'text' or IS-A it"), message);
    }

    @Test
    void aFieldRestatedAsItsSupertypeIsRefused() {
        String message = refused("""
                {
                  person   => { name: text }
                  employee => person & { badge: text }
                  team     => { lead: employee }
                  loose    => team & { lead: person }
                }
                """);

        assertTrue(message.contains("field 'lead' is typed 'person', which is not 'employee' or IS-A it"), message);
    }

    @Test
    void aRestatementBesideANewFieldFollowsTheSameRule() {
        String message = refused("""
                {
                  a => { x: text }
                  b => a & { x: int32  y: text }
                }
                """);

        assertTrue(message.contains("'b': not a valid restatement of 'a''s fields (§5.8): field 'x' is typed 'int32'"),
                message);
    }

    @Test
    void narrowingToASubtypeIsAdmitted() {
        TsonLinkedSchema linked = link("""
                {
                  person    => { name: text }
                  employee  => person & { badge: text }
                  team      => { lead: person  size: integer }
                  staffed   => team & { lead: employee }
                  counted   => team & { size: count  label: text }
                  count     => !integer ^ { min: 0 }
                }
                """);

        RecordBody staffed = assertInstanceOf(RecordBody.class, linked.schema().entries().get("staffed"));
        assertEquals("employee", staffed.fields().get("lead").typeExpr().text());
        RecordBody counted = assertInstanceOf(RecordBody.class, linked.schema().entries().get("counted"));
        assertEquals("count", counted.fields().get("size").typeExpr().text());
    }

    @Test
    void theSameTypeAnAliasOfItAndVoidAtAVoidablePositionAreAdmitted() {
        assertDoesNotThrow(() -> link("""
                {
                  label     => text
                  a         => { x: text  y?: text?  z: [int32] }
                  same      => a & { x: text  z: [int32] }
                  renamed   => a & { x: label }
                  forbidden => a & { y?: void? }
                }
                """));
    }

    /** A reference narrowed to a refinement of it, as the kernel's `meta.namespace` narrows `meta.map`'s `key_type`. */
    @Test
    void aReferenceRefinementIsANarrowingOfItsSource() {
        TsonLinkedSchema linked = link("""
                {
                  slot  => { of: meta.type_ref }
                  names => slot & { of: meta.identifier_ref }
                }
                """);

        RecordBody namespace = assertInstanceOf(RecordBody.class, linked.schema().entries().get("meta.namespace"));
        assertEquals("meta.identifier_ref", namespace.fields().get("key_type").typeExpr().text());
        RecordBody map = assertInstanceOf(RecordBody.class, linked.schema().entries().get("meta.map"));
        assertEquals("meta.type_ref", map.fields().get("key_type").typeExpr().text());
    }

    /** No structural IS-A between two containers: two instances of `array` are unrelated (§7.2). */
    @Test
    void aContainerOfASubtypeIsNoNarrowing() {
        String message = refused("""
                {
                  a => { x: [int32] }
                  b => a & { x: [int8] }
                }
                """);

        assertTrue(message.contains("field 'x' is typed '[int8]', which is not '[int32]' or IS-A it"), message);
    }

    /** A variant is a member of its choice, not IS-A it: §7.2 states the two relations apart. */
    @Test
    void aVariantOfAChoiceIsNoNarrowing() {
        String message = refused("""
                {
                  either => (int32 | text)
                  a      => { x: either }
                  b      => a & { x: int32 }
                }
                """);

        assertTrue(message.contains("field 'x' is typed 'int32', which is not 'either' or IS-A it"), message);
    }

    /** A restatement over a closed application is judged against the fields that application closed with. */
    @Test
    void aRestatementOverAClosedApplicationIsJudgedAgainstItsSubstitutedFields() {
        String message = refused("""
                {
                  box  => <T> { v: T }
                  held => box<text>
                  b    => held & { v: int32 }
                }
                """);

        assertTrue(message.contains("'b': not a valid restatement of 'held''s fields (§5.8): field 'v' is typed 'int32', "
                + "which is not 'text' or IS-A it"), message);
    }

    private static final String PET_OF = """
              dog_type   => { bark: text }
              cat_type   => { purr: text }
              puppy_type => dog_type & { age: int32 }
              pet        => { name: text }
              pet_of     => <T> pet & { pet: T }
            """;

    @Test
    void aFieldAbsorbedFromAnApplicationNoDeclarationNamesIsRestated() {
        String message = refused("{\n" + PET_OF + "  dog => pet_of<dog_type> & { pet: cat_type }\n}\n");

        assertTrue(message.contains("'dog': not a valid restatement of 'pet_of<dog_type>''s fields (§5.8): field "
                + "'pet' is typed 'cat_type', which is not 'dog_type' or IS-A it"), message);
    }

    @Test
    void anAbsorbedFieldIsRefusedAlikeWhereADeclarationNamesTheApplication() {
        String message = refused("{\n" + PET_OF + "  dog_pet => pet_of<dog_type>\n"
                + "  dog => pet_of<dog_type> & { pet: cat_type }\n}\n");

        assertTrue(message.contains("'dog': not a valid restatement of 'dog_pet''s fields (§5.8): field 'pet' is "
                + "typed 'cat_type', which is not 'dog_type' or IS-A it"), message);
    }

    @Test
    void anAbsorbedFieldNarrowsToASubtype() {
        TsonLinkedSchema linked = link("{\n" + PET_OF + "  dog => pet_of<dog_type> & { pet: puppy_type }\n}\n");

        RecordBody dog = assertInstanceOf(RecordBody.class, linked.schema().entries().get("dog"));
        assertEquals("puppy_type", dog.fields().get("pet").typeExpr().text());
    }

    /** `wrap`'s `v: int32` restates `box<T>`'s `v: T`, judged with each application's arguments (§5.10). */
    private static final String WRAP = """
              box  => <T> { v: T }
              wrap => <T> box<T> & { v: int32 }
            """;

    @Test
    void aParametricRecordsRestatementIsJudgedWhereItsApplicationIsDeclared() {
        String message = refused("{\n" + WRAP + "  wrap_text => wrap<text>\n}\n");

        assertTrue(message.contains("'wrap_text': not a valid restatement of 'box<text>''s fields (§5.8): field 'v' "
                + "is typed 'int32', which is not 'text' or IS-A it"), message);
        assertDoesNotThrow(() -> link("{\n" + WRAP + "  wrap_int32 => wrap<int32>\n}\n"));
    }

    @Test
    void aParametricRecordsRestatementIsJudgedWhereItsApplicationIsAbsorbed() {
        String message = refused("{\n" + WRAP + "  more => wrap<text> & { note: text }\n}\n");

        assertTrue(message.contains("'more': not a valid restatement of 'box<text>''s fields (§5.8)"), message);
    }

    @Test
    void aParametricRecordsRestatementIsJudgedThroughAnotherParametricRecord() {
        String message = refused("{\n" + WRAP + "  rewrap => <T> wrap<T> & {}\n  x => rewrap<text>\n}\n");

        assertTrue(message.contains("'x': not a valid restatement of 'box<text>''s fields (§5.8)"), message);
    }

    /** A record in place is no entry, so the entry holding it answers for its restatement. */
    @Test
    void aParametricRecordsRestatementIsJudgedWhereItsApplicationClosesInPlace() {
        String message = refused("{\n" + WRAP + "  holder => { w: wrap<text> }\n}\n");

        assertTrue(message.contains("'holder': not a valid restatement of 'box<text>''s fields (§5.8)"), message);
    }

    /**
     * Two applications are one where their canonical applications are (§8.2): an argument naming an alias is its
     * terminal, so the restatement is the identity, whether or not a declaration names the operand's application.
     */
    @Test
    void anAbsorbedApplicationRestatedThroughAnAliasArgumentIsTheIdentity() {
        String schema = """
                  item => <T> { x: T }
                  box  => <T> { v: item<T> }
                  name => text
                  w    => box<text> & { v: item<name> }
                """;
        assertDoesNotThrow(() -> link("{\n" + schema + "}\n"));
        assertDoesNotThrow(() -> link("{\n" + schema + "  bt => box<text>\n}\n"));
        String message = refused("{\n" + schema.replace("v: item<name>", "v: item<int32>") + "}\n");
        assertTrue(message.contains("'w': not a valid restatement of 'box<text>''s fields (§5.8)"), message);
    }

    /** §5.9 rule 4: a subtraction's tightening entries follow §5.8's rules, though its chain is empty. */
    @Test
    void aSubtractionsTighteningEntryIsJudged() {
        String message = refused("""
                {
                  account => { name: text  password: text }
                  view    => account & { name: int32 } - { password }
                }
                """);

        assertTrue(message.contains("'view': not a valid restatement of 'account''s fields (§5.8): field 'name' is "
                + "typed 'int32', which is not 'text' or IS-A it"), message);
        assertDoesNotThrow(() -> link("""
                {
                  person   => { name: text }
                  employee => person & { badge: text }
                  account  => { owner: person  password: text }
                  view     => account & { owner: employee } - { password }
                }
                """));
    }

    @Test
    void aParametricSubtractionsTighteningEntryIsJudgedWithEachApplicationsArguments() {
        String message = refused("""
                {
                  box  => <T> { v: T  w: text }
                  sub  => <T> box<T> & { v: int32  extra: T } - { w }
                  x    => sub<text>
                }
                """);

        assertTrue(message.contains("'x': not a valid restatement of 'box<text>''s fields (§5.8)"), message);
    }

    /** An imported parametric record's restatements travel with it to the schema applying it. */
    @Test
    void anImportedParametricRecordsRestatementIsJudgedWhereItIsApplied() {
        String library = """
                !!id:"https://example.test/wrap.tn"
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !!import:"https://tson.io/2026/40/m/core.tn"
                !schema {
                """ + WRAP + "}\n";
        String importer = """
                !!id:"https://example.test/restated.tn"
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !!import:"https://tson.io/2026/40/m/core.tn"
                !!import:"https://example.test/wrap.tn"
                !schema {
                  wrap_text => wrap<text>
                }
                """;
        String message = assertThrows(SchemaValidationException.class, () ->
                TsonCompiledVocabularyRegistry.withStandardLibrary(SchemaModelNameBinder.defaultContext(), uri -> {
                    if (CanonicalIdentity.sameIdentity(uri, ID)) {
                        return importer;
                    }
                    if (CanonicalIdentity.sameIdentity(uri, "https://example.test/wrap.tn")) {
                        return library;
                    }
                    throw new IllegalStateException("unexpected fetch: " + uri);
                }).resolveLinked(ID)).getMessage();

        assertTrue(message.contains("'wrap_text': not a valid restatement of 'box<text>''s fields (§5.8)"), message);
    }
}
