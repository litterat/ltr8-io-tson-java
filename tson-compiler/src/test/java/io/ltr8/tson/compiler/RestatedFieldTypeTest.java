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
 * [TSON-SCHEMA] §5.7's rule for a restated field's type, and §5.8's application of it to a composition's
 * tightening entries: the restated type is the inherited one or a type IS-A it (§7.2), or {@code void} at a voidable
 * position. Judged at link, where every name both types hold resolves.
 */
class RestatedFieldTypeTest {

    private static final String ID = "https://example.test/restated.tn";

    private static TsonLinkedSchema link(String body) {
        String schema = """
                !!id:"https://example.test/restated.tn"
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
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
    void aRefinementRestatingAFieldAsAnUnrelatedTypeIsRefused() {
        String message = refused("""
                {
                  a => { x: text }
                  b => a ^ { x: int32 }
                }
                """);

        assertTrue(message.contains("'b': not a valid refinement of 'a' (§5.7): field 'x' is typed 'int32', "
                + "which is not 'text' or IS-A it"), message);
    }

    @Test
    void aRefinementRestatingAFieldAsItsSupertypeIsRefused() {
        String message = refused("""
                {
                  person   => { name: text }
                  employee => person & { badge: text }
                  team     => { lead: employee }
                  loose    => team ^ { lead: person }
                }
                """);

        assertTrue(message.contains("field 'lead' is typed 'person', which is not 'employee' or IS-A it"), message);
    }

    @Test
    void aCompositionsTighteningEntryFollowsTheSameRule() {
        String message = refused("""
                {
                  a => { x: text }
                  b => a & { x: int32  y: text }
                }
                """);

        assertTrue(message.contains("'b': not a valid refinement of 'a' (§5.7): field 'x' is typed 'int32'"), message);
    }

    @Test
    void narrowingToASubtypeIsAdmitted() {
        TsonLinkedSchema linked = link("""
                {
                  person    => { name: text }
                  employee  => person & { badge: text }
                  team      => { lead: person  size: integer }
                  staffed   => team ^ { lead: employee }
                  counted   => team & { size: count  label: text }
                  count     => !integer ^ { min: 0 }
                }
                """);

        RecordBody staffed = assertInstanceOf(RecordBody.class, linked.schema().entries().get("staffed"));
        assertEquals("employee", staffed.fields().get("lead").typeRef().name());
        RecordBody counted = assertInstanceOf(RecordBody.class, linked.schema().entries().get("counted"));
        assertEquals("count", counted.fields().get("size").typeRef().name());
    }

    @Test
    void theSameTypeAnAliasOfItAndVoidAtAVoidablePositionAreAdmitted() {
        assertDoesNotThrow(() -> link("""
                {
                  label     => text
                  a         => { x: text  y?: text?  z: [int32] }
                  same      => a ^ { x: text  z: [int32] }
                  renamed   => a ^ { x: label }
                  forbidden => a ^ { y?: void? }
                }
                """));
    }

    /** A reference narrowed to a refinement of it, as the kernel's `meta.namespace` narrows `meta.map`'s `key_type`. */
    @Test
    void aReferenceRefinementIsANarrowingOfItsSource() {
        TsonLinkedSchema linked = link("""
                {
                  slot  => { of: meta.type_ref }
                  names => slot ^ { of: meta.identifier_ref }
                }
                """);

        RecordBody namespace = assertInstanceOf(RecordBody.class, linked.schema().entries().get("meta.namespace"));
        assertEquals("meta.identifier_ref", namespace.fields().get("key_type").typeRef().name());
        RecordBody map = assertInstanceOf(RecordBody.class, linked.schema().entries().get("meta.map"));
        assertEquals("meta.type_ref", map.fields().get("key_type").typeRef().name());
    }

    /** No structural IS-A between two containers: two instances of `array` are unrelated (§7.2). */
    @Test
    void aContainerOfASubtypeIsNoNarrowing() {
        String message = refused("""
                {
                  a => { x: [int32] }
                  b => a ^ { x: [int8] }
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
                  b      => a ^ { x: int32 }
                }
                """);

        assertTrue(message.contains("field 'x' is typed 'int32', which is not 'either' or IS-A it"), message);
    }

    /** A refinement of a closed application is judged against the fields that application closed with. */
    @Test
    void aRefinementOfAClosedApplicationIsJudgedAgainstItsSubstitutedFields() {
        String message = refused("""
                {
                  box  => <T> { v: T }
                  held => box<text>
                  b    => held ^ { v: int32 }
                }
                """);

        assertTrue(message.contains("'b': not a valid refinement of 'held' (§5.7): field 'v' is typed 'int32', "
                + "which is not 'text' or IS-A it"), message);
    }
}
