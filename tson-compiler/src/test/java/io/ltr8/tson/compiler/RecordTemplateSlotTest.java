package io.ltr8.tson.compiler;

import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.TypeInline;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a template's parameters may stand ([TSON-SCHEMA] §5.10): a parameter is a type, so it stands at a field's
 * type or as an argument of an application. Anywhere else -- a field's value, a scalar slot of any constructor -- is
 * refused at the declaration, naming the slot, and a parameter is never itself the operand of {@code &} or {@code ^}.
 */
class RecordTemplateSlotTest {

    private static final String ID = "https://example.test/record-template-slots.tn";

    private static TsonCompiledSchema compile(String declarations) {
        String schema = """
                !!id:"https://example.test/record-template-slots.tn"
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !!import:"https://tson.io/2026/40/m/core.tn"
                !schema {
                %s
                }
                """.formatted(declarations);
        SchemaSource source = uri -> {
            if (CanonicalIdentity.sameIdentity(uri, ID)) {
                return schema;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        TsonCompiledVocabularyRegistry core =
                TsonCompiledVocabularyRegistry.withStandardLibrary(SchemaModelNameBinder.defaultContext(), source);
        return TsonCompiledSchemaRegistry.tree(core).get(ID);
    }

    private static String rejected(String declarations) {
        return assertThrows(SchemaValidationException.class, () -> compile(declarations)).getMessage();
    }

    /**
     * An application of an open record left in another's body by closing it closes in place there ([TSON-SCHEMA]
     * §5.10): {@code bb}'s {@code items} holds the record {@code box<text>} closes to, with no name.
     */
    @Test
    void aRecordWhoseBodyAppliesAnotherOpenRecordClosesItInPlace() {
        TsonCompiledSchema compiled = compile("""
                  box => <T> { v: T }
                  boxes => <T> { items: box<T> }
                  bb => boxes<text>
                """);
        RecordBody bb = (RecordBody) compiled.schema().entries().get("bb");
        TypeInline items = assertInstanceOf(TypeInline.class, bb.fields().get("items").type());
        assertEquals("box<text>", items.text());
        assertEquals(TypeRef.of("text"), ((RecordBody) items.of()).fields().get("v").type());
    }

    /**
     * A parameter at a type slot inside a type written in place, in an explicit payload, is no author's error but is
     * not yet supported: the parameters are scoped and substituted at the field types and their applications, and
     * neither reaches inside the wrapper. The sugar spelling, {@code [T]}, is the application and is admitted.
     */
    @Test
    void aParameterInsideATypeWrittenInPlaceIsNotYetSupported() {
        Map<String, String> gaps = Map.of(
                "  arr => <T> !meta.record { fields: { v => { type: { of: !meta.array { element_type: T } } } } }",
                "parameter 'T' stands at meta.array.element_type, inside a type written in place",
                "  deep => <T> !meta.record { fields: { v => { type: { of: !meta.map { key_type: text  value_type: "
                        + "{ of: !meta.array { element_type: T } } } } } } }",
                "parameter 'T' stands at meta.array.element_type, inside a type written in place",
                "  pick => <T> !meta.record { fields: { v => { type: { of: !meta.choice { variants: [text T] } } } } }",
                "parameter 'T' stands at meta.choice.variants, inside a type written in place");
        gaps.forEach((declaration, expected) -> {
            UnsupportedOperationException gap = assertThrows(UnsupportedOperationException.class,
                    () -> compile(declaration), declaration);
            assertTrue(gap.getMessage().contains(expected), gap.getMessage());
        });
        assertDoesNotThrow(() -> compile("""
                  arr => <T> { v: [T] }
                  a => arr<text>
                """));
    }

    /** A parameter at a slot that holds no type is the author's error inside a type written in place as outside it. */
    @Test
    void aParameterAtAScalarSlotInsideATypeWrittenInPlaceIsRefused() {
        String message = rejected("  sized => <N> !meta.record { fields: { v => { type: { of: !meta.array { "
                + "element_type: text  min_items: N } } } } }");
        assertTrue(message.contains("parameter 'N' stands at meta.array.min_items"), message);
    }

    /** The two places, each applied so the closing runs as well as the declaration. */
    @Test
    void aParameterStandsInAFieldsTypeAndAnApplicationsArguments() {
        List<String> admitted = List.of(
                """
                  box => <T> { v: T }
                  b => box<text>
                """,
                """
                  result => abstract <T> { payload: T }
                  ok => <T> result<T> & { note: text }
                  ok_text => ok<text>
                """,
                """
                  vec => <T> { items: [T] }
                  v3 => vec<text>
                """);
        for (String declarations : admitted) {
            assertDoesNotThrow(() -> compile(declarations), declarations);
        }
    }

    /** Every other slot of a record body, each reported at the declaration and named by the slot it is in. */
    @Test
    void aParameterAnywhereElseInARecordBodyIsRefusedAtTheDeclaration() {
        Map<String, String> refused = Map.of(
                "  pinned => <N> { w?: int32 ~ N }", "parameter 'N' stands at the value of field 'w'",
                "  keyed => <K> { K: text }", "parameter 'K' stands at a key of meta.record.fields",
                "  grouped => <A> { ( A: text | b: text ) }", "parameter 'A' stands at a key of meta.record.fields",
                "  maybe => <B> !meta.record { fields: { v => { type: text  optional: B } } }",
                "parameter 'B' stands at meta.record_field.optional",
                "  nulls => <V> !meta.record { fields: { v => { type: text  voidable: V } } }",
                "parameter 'V' stands at meta.record_field.voidable",
                "  roled => <R> !meta.record { fields: { v => { type: text  role: R  value: \"x\" } } }",
                "parameter 'R' stands at meta.record_field.role",
                "  realised => <E> !meta.record { fields: { v => { type: text } }  extension: E }",
                "parameter 'E' stands at meta.record.extension",
                "  named => <I> !meta.record { fields: { v => { type: text } }  name_type: I }",
                "parameter 'I' stands at meta.record.name_type",
                "  fam => <D> !meta.record { fields: { kind => { type: text } }  extension: ABSTRACT  discriminators: [D] }",
                "parameter 'D' stands at meta.record.discriminators");
        refused.forEach((declaration, expected) -> {
            String message = rejected(declaration);

            assertTrue(message.contains(expected), message);
            assertTrue(message.contains("a parameter is a type, and stands only at a field's type"), message);
        });
    }

    /** Another constructor takes no parameter at all: a template's body is a record body (§5.10). */
    @Test
    void aParameterListBeforeAnotherConstructorIsRefused() {
        String voidable = rejected("""
                  pair => <V> !meta.tuple { elements: [{ element_type: text  voidable: V } { element_type: int32 }] }
                """);
        String capped = rejected("""
                  capped => <N> !meta.array { element_type: text  min_items: N }
                """);

        assertTrue(voidable.contains("a template's body is a record body"), voidable);
        assertTrue(capped.contains("a template's body is a record body"), capped);
    }

    /** A parameter has no fields until the template is applied, so neither operator has a body to flatten. */
    @Test
    void aParameterIsNeverAnOperand() {
        String composed = rejected("""
                  mixin => <T> T & { name: text }
                  person => { age: int32 }
                  m => mixin<person>
                """);
        String refined = rejected("""
                  narrowed => <T> T ^ { }
                  person => { age: int32 }
                  m => narrowed<person>
                """);

        assertTrue(composed.contains("supertype 'T' is a parameter of this declaration, and a parameter is never an "
                + "operand of '&'"), composed);
        assertTrue(refined.contains("refinement source 'T' is a parameter of this declaration, and a parameter is "
                + "never an operand of '^'"), refined);
    }

    /**
     * §5.10's shadowing rule: a parameter spelled like a schema entry is refused at its declaration, so a template
     * whose operand would be that parameter is refused for the shadowing before the operand is read.
     */
    @Test
    void aParameterShadowingAnEntryIsRefusedBeforeItsOperand() {
        String message = rejected("""
                  T => { age: int32 }
                  mixin => <T> T & { name: text }
                """);

        assertTrue(message.contains("'mixin': parameter 'T' shadows the entry 'T' this schema's namespace holds"),
                message);
    }
}
