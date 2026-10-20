package io.ltr8.tson.compiler;

import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a record template's parameters may stand (SPEC-FEEDBACK.md #16): a field's type, a field's default or fixed
 * value, and an argument of an application. Anywhere else in a {@code !record} body would leave the family base
 * without a shape its applications share, and a parameter is never itself the operand of {@code &} or {@code ^}.
 */
class RecordTemplateSlotTest {

    private static final String ID = "https://example.test/record-template-slots.tn";

    private static TsonCompiledSchema compile(String declarations) {
        String schema = """
                !!id:"https://example.test/record-template-slots.tn"
                !!meta:"https://tson.io/2026/38/m/meta.tn"
                !!import:"https://tson.io/2026/38/m/core.tn"
                {
                %s
                }
                """.formatted(declarations);
        SchemaSource source = uri -> {
            if (CanonicalIdentity.sameIdentity(uri, ID)) {
                return schema;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        TsonCompiledMetaRegistry core =
                TsonCompiledMetaRegistry.withStandardLibrary(SchemaMetaNameBinder.defaultContext(), source);
        return TsonCompiledSchemaRegistry.tree(core).get(ID);
    }

    private static String rejected(String declarations) {
        return assertThrows(SchemaValidationException.class, () -> compile(declarations)).getMessage();
    }

    /** The three places, each applied so the closing runs as well as the declaration. */
    @Test
    void aParameterStandsInAFieldsTypeItsValueAndAnApplicationsArguments() {
        List<String> admitted = List.of(
                """
                  box => <T> { v: T }
                  b => box<text>
                """,
                """
                  box => <T> { v: T }
                  boxes => <T> { items: box<T> }
                  bb => boxes<text>
                """,
                """
                  result => abstract <T> { payload: T }
                  ok => <T> result<T> & { note: text }
                  ok_text => ok<text>
                """,
                """
                  pet => <N> { type: text = N  name: text }
                  cat => pet<"cat">
                """,
                """
                  sized => <N> { w?: int32 ~ N }
                  s => sized<3>
                """,
                """
                  vec => <T, N> { items: [T; N] }
                  v3 => vec<text, 3>
                """);
        for (String declarations : admitted) {
            assertDoesNotThrow(() -> compile(declarations), declarations);
        }
    }

    /** Every other slot of a record body, each reported at the declaration and named by the slot it is in. */
    @Test
    void aParameterAnywhereElseInARecordBodyIsRefusedAtTheDeclaration() {
        Map<String, String> refused = Map.of(
                "  keyed => <K> { K: text }", "parameter 'K' stands at a key of record.fields",
                "  grouped => <A> { ( A: text | b: text ) }", "parameter 'A' stands at a key of record.fields",
                "  maybe => <B> !record { fields: { v => { type: text  optional: B } } }",
                "parameter 'B' stands at record_field.optional",
                "  nulls => <V> !record { fields: { v => { type: text  voidable: V } } }",
                "parameter 'V' stands at record_field.voidable",
                "  roled => <R> !record { fields: { v => { type: text  role: R  value: \"x\" } } }",
                "parameter 'R' stands at record_field.role",
                "  realised => <E> !record { fields: { v => { type: text } }  extension: E }",
                "parameter 'E' stands at record.extension",
                "  named => <I> !record { fields: { v => { type: text } }  name_type: I }",
                "parameter 'I' stands at record.name_type",
                "  fam => <D> !record { fields: { kind => { type: text } }  extension: ABSTRACT  discriminators: [D] }",
                "parameter 'D' stands at record.discriminators");
        refused.forEach((declaration, expected) -> {
            String message = rejected(declaration);

            assertTrue(message.contains(expected), message);
            assertTrue(message.contains("a record template's parameters stand only in a field's type"), message);
        });
    }

    /** The confinement is a record template's: another constructor's scalar slot still takes a value parameter. */
    @Test
    void aTemplateOverAnotherConstructorKeepsItsScalarSlots() {
        assertDoesNotThrow(() -> compile("""
                  pair => <V> !tuple { elements: [{ element_type: text  voidable: V } { element_type: int32 }] }
                  p => pair<true>
                  bounded => <N> !array { element_type: text  min_items: N }
                  b => bounded<2>
                """));
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

    /** §5.10's shadowing rule: a parameter hides a schema entry of the same name, at an operand as anywhere. */
    @Test
    void aParameterShadowsAnEntryOfTheSameNameAtAnOperand() {
        String message = rejected("""
                  T => { age: int32 }
                  mixin => <T> T & { name: text }
                """);

        assertTrue(message.contains("supertype 'T' is a parameter of this declaration"), message);
    }
}
