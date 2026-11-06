package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.TsonTreeReader;
import io.ltr8.tson.compiler.TsonTreeWriter;
import io.ltr8.tson.tree.TsonSiblingReference;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A sibling reference, {@code kind!} ([TSON-DATA] §7.4, [TSON-SCHEMA] §5.2, SPEC-FEEDBACK.md #21), end to end: the
 * reference atom reads one wherever a reference stands, and the position rule refuses it everywhere but a record
 * field's type -- in sugar where it is written, in an explicit instance at the slot reader, and in data at a field
 * typed by a type reference. At a field's type it reaches the model, where reading against it is not implemented yet.
 */
class SiblingReferenceTest {

    private static final String ID = "https://example.test/sibling.tn";

    private static String schema(String declarations) {
        return """
                !!id:"%s"
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !schema {
                %s
                }""".formatted(ID, declarations);
    }

    private static String refused(String slot) {
        return "a sibling reference ('kind!') stands only at a record field's type ([TSON-SCHEMA] §5.2), not at "
                + slot;
    }

    private static Diagnostic only(List<Diagnostic> problems) {
        assertEquals(1, problems.size(), () -> problems.toString());
        return problems.getFirst();
    }

    /** An explicit instance's slot typed {@code TypeRef} refuses a sibling at its token, naming the slot. */
    @Test
    void anExplicitInstanceRefusesASiblingAtEverySlotButAFieldsType() {
        Map<String, String> slots = Map.of(
                "x => !meta.array { element_type: kind! }", "field 'element_type'",
                "x => !meta.alias { target: kind! }", "field 'target'",
                "x => !meta.map { key_type: text  value_type: kind! }", "field 'value_type'",
                "x => !meta.choice { variants: [kind! text] }", "field 'variants''s element");
        slots.forEach((declaration, slot) -> {
            Diagnostic problem = only(Tson.standard().validateSchema(schema(declaration)));
            assertEquals(Diagnostic.Code.SCHEMA_ERROR, problem.code(), declaration);
            assertTrue(problem.message().contains(refused(slot)), problem.message());
        });
    }

    /** Sugar refuses it before any lift, through the front door as in the phase itself. */
    @Test
    void sugarRefusesASiblingOffAFieldsType() {
        Diagnostic problem = only(Tson.standard().validateSchema(schema("cell => { kind: meta.type_ref  v: [ kind! ] }")));
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, problem.code());
        assertEquals(refused("an array's element type"), problem.message());
    }

    /**
     * At a record field's type, in every spelling -- sugar, an explicit {@code meta.record_field}, the positional form
     * and a record template's field -- the sibling reaches the model and meets this library's gap, never the
     * position rule.
     */
    @Test
    void aSiblingAtAFieldsTypeReachesTheModel() {
        for (String declaration : List.of(
                "cell => { kind: meta.type_ref  v?: kind! }",
                "cell => !meta.record { fields: { kind => meta.type_ref  v => !meta.record_field { type: kind! } } }",
                "cell => !meta.record { fields: { kind => meta.type_ref  v => kind! } }",
                "cell => <T> { kind: meta.type_ref  v?: kind!  w: T }\n  used => { c: cell<text> }")) {
            // The linker's field checks throw the gap rather than report it, which is the next step's to replace.
            List<String> gaps;
            try {
                gaps = Tson.standard().validateSchema(schema(declaration)).stream()
                        .map(problem -> problem.code() + " " + problem.message()).toList();
            } catch (UnsupportedOperationException gap) {
                gaps = List.of(Diagnostic.Code.NOT_IMPLEMENTED + " " + gap.getMessage());
            }
            List<String> reported = gaps;
            assertTrue(!reported.isEmpty() && reported.stream().allMatch(gap -> gap.startsWith("NOT_IMPLEMENTED ")
                    && gap.contains("a sibling reference ('kind!') at a record field's type")),
                    () -> declaration + " -> " + reported);
        }
    }

    /**
     * In data there is no component to narrow by: a field typed {@code meta.type_ref} holds a type's name, and the
     * reference atom refuses a sibling there. A quoted {@code "kind!"} is text, never a reference.
     */
    @Test
    void dataRefusesASiblingAtAFieldTypedByATypeReference() {
        Tson tson = Tson.of(ProcessorConfig.defaults().withSchemaAccess(
                SchemaAccess.of(SchemaSource.ofMap(Map.of(ID, schema("typed => { t: meta.type_ref }"))))));
        Diagnostic sibling = only(tson.validate("!!import:\"" + ID + "\"\n!typed { t: kind! }"));
        assertEquals(Diagnostic.Code.ATOM_FORM_INVALID, sibling.code());
        assertEquals(java.util.Optional.of("/t"), sibling.path());
        assertTrue(sibling.message().contains(refused("a position typed by a type reference")), sibling.message());

        Diagnostic quoted = only(tson.validate("!!import:\"" + ID + "\"\n!typed { t: \"kind!\" }"));
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, quoted.code());
    }

    /** A schemaless read keeps it as a structured value, and the writer writes it back with whitespace after. */
    @Test
    void aSchemalessReadKeepsASiblingAndWritesItBack() {
        TsonValue read = new TsonTreeReader().read("{ type: kind!  in: [ a kind! ]  m: { kind! => x } }");
        TsonSiblingReference sibling = assertInstanceOf(TsonSiblingReference.class, read.get("type"));
        assertEquals("kind", sibling.field());
        assertTrue(sibling.isReference());
        String written = new TsonTreeWriter().toTson(read);
        assertTrue(written.contains("type: kind! "), written);
        assertEquals(read, new TsonTreeReader().read(written));
    }
}
