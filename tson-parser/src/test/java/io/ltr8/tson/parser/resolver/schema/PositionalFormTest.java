package io.ltr8.tson.parser.resolver.schema;

import io.ltr8.tson.parser.SchemaParser;
import io.ltr8.tson.parser.ast.ArrayValue;
import io.ltr8.tson.parser.ast.DataValue;
import io.ltr8.tson.parser.ast.EmptyBrace;
import io.ltr8.tson.parser.ast.RecordValue;
import io.ltr8.tson.parser.ast.ScopedValue;
import io.ltr8.tson.parser.ast.TokenForm;
import io.ltr8.tson.parser.ast.TokenValue;
import io.ltr8.tson.parser.ast.schema.SchemaDocument;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.FieldState;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Token;
import io.ltr8.tson.schema.meta.TypeRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link PositionalForm#normalizeToRecordForm} on its own, isolated from any {@code Instance}/
 * {@code SchemaResolver} wiring (that comes later, Phase B steps 4-6) -- exercises the field-name
 * lookup (§5.6's "exactly one bare REQUIRED field" rule) and the wrap-vs-pass-through decision
 * against hand-built {@link RecordBody} values, then confirms the same logic against {@code enum}'s
 * own real resolved shape ({@code enum => ~atom & { members: set<token> }}).
 */
class PositionalFormTest {

    private static final RecordBody ONE_REQUIRED_FIELD =
            RecordBody.of(List.of(RecordField.required("encoding", TypeRef.of("token"))));

    private static DataValue bareToken(String text) {
        return new DataValue(List.of(), Optional.empty(), new TokenValue(text, TokenForm.UNQUOTED));
    }

    @Test
    void recordValuePassesThroughUnchanged() {
        DataValue value = new DataValue(List.of(), Optional.empty(),
                new RecordValue(List.of(new RecordValue.Field("encoding", new ScopedValue(Optional.empty(), bareToken("HEX"))))));

        DataValue result = PositionalForm.normalizeToRecordForm(value, ONE_REQUIRED_FIELD);

        assertSame(value, result);
    }

    @Test
    void emptyBracePassesThroughUnchanged() {
        DataValue value = new DataValue(List.of(), Optional.empty(), new EmptyBrace());

        DataValue result = PositionalForm.normalizeToRecordForm(value, ONE_REQUIRED_FIELD);

        assertSame(value, result);
    }

    @Test
    void bareTokenWrapsIntoTheSoleRequiredField() {
        DataValue value = new DataValue(List.of(), Optional.of("binary"), new TokenValue("HEX", TokenForm.UNQUOTED));

        DataValue result = PositionalForm.normalizeToRecordForm(value, ONE_REQUIRED_FIELD);

        assertEquals(Optional.of("binary"), result.typeRef());
        assertEquals(List.of(), result.annotations());
        RecordValue record = (RecordValue) result.coreValue();
        assertEquals(1, record.fields().size());
        assertEquals("encoding", record.fields().get(0).name());
        DataValue fieldValue = record.fields().get(0).value().value();
        assertEquals(new TokenValue("HEX", TokenForm.UNQUOTED), fieldValue.coreValue());
        // The synthesized field value carries neither annotations nor a type-ref of its own --
        // both belonged to the whole (now outer) instance, not specifically to this field.
        assertEquals(List.of(), fieldValue.annotations());
        assertEquals(Optional.empty(), fieldValue.typeRef());
    }

    @Test
    void bareArrayWrapsIntoTheSoleRequiredField() {
        RecordBody membersField =
                RecordBody.of(List.of(RecordField.required("members", TypeRef.of("token"))));
        ArrayValue array = new ArrayValue(List.of(
                new ScopedValue(Optional.empty(), bareToken("true")),
                new ScopedValue(Optional.empty(), bareToken("false"))));
        DataValue value = new DataValue(List.of(), Optional.of("enum"), array);

        DataValue result = PositionalForm.normalizeToRecordForm(value, membersField);

        RecordValue record = (RecordValue) result.coreValue();
        assertEquals(1, record.fields().size());
        assertEquals("members", record.fields().get(0).name());
        assertEquals(array, record.fields().get(0).value().value().coreValue());
    }

    @Test
    void zeroBareRequiredFieldsIsRejected() {
        RecordBody allOptional = RecordBody.of(List.of(
                new RecordField("min", TypeRef.of("integer"), FieldState.OPTIONAL, Optional.empty(), Optional.empty())));

        assertThrows(UnsupportedOperationException.class,
                () -> PositionalForm.normalizeToRecordForm(bareToken("42"), allOptional));
    }

    @Test
    void multipleBareRequiredFieldsIsRejected() {
        RecordBody twoRequired = RecordBody.of(List.of(
                RecordField.required("a", TypeRef.of("token")),
                RecordField.required("b", TypeRef.of("token"))));

        assertThrows(UnsupportedOperationException.class,
                () -> PositionalForm.normalizeToRecordForm(bareToken("x"), twoRequired));
    }

    @Test
    void aRequiredDefaultFieldDoesNotCountAsBareRequired() {
        // §5.6: only a *bare* REQUIRED field is positionally fillable -- REQUIRED_DEFAULT (a field
        // with a default value, "~") is a different state entirely, even though it's still
        // mandatory in the sense that it always has a value.
        RecordBody requiredDefaultOnly = RecordBody.of(List.of(
                new RecordField("encoding", TypeRef.of("token"), FieldState.REQUIRED_DEFAULT,
                        Optional.of(new Token("HEX", Token.Form.UNQUOTED)), Optional.empty())));

        assertThrows(UnsupportedOperationException.class,
                () -> PositionalForm.normalizeToRecordForm(bareToken("HEX"), requiredDefaultOnly));
    }

    // ── Against enum's own real resolved shape ─────────────────────────────

    @Test
    void wrapsAnEnumInstanceValueUsingEnumsOwnRealResolvedBody() {
        SchemaDocument doc = new SchemaParser("""
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn1"
                {
                  top => {}
                  atom => top & {}
                  enum => ~atom & { members: set<token> }
                }""").parseSchemaDocument();
        TsonSchema schema = new SchemaResolver().resolveAll(doc);
        RecordBody enumBody = (RecordBody) schema.entries().get("enum").body();

        ArrayValue booleanMembers = new ArrayValue(List.of(
                new ScopedValue(Optional.empty(), bareToken("true")),
                new ScopedValue(Optional.empty(), bareToken("false"))));
        DataValue instanceValue = new DataValue(List.of(), Optional.of("enum"), booleanMembers);

        DataValue result = PositionalForm.normalizeToRecordForm(instanceValue, enumBody);

        RecordValue record = (RecordValue) result.coreValue();
        assertEquals(1, record.fields().size());
        assertEquals("members", record.fields().get(0).name());
        assertEquals(booleanMembers, record.fields().get(0).value().value().coreValue());
    }
}
