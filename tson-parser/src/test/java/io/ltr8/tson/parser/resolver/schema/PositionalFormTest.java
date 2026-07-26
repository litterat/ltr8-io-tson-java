package io.ltr8.tson.parser.resolver.schema;

import io.ltr8.tson.parser.TsonSchemaParser;
import io.ltr8.tson.parser.ast.ArrayValue;
import io.ltr8.tson.parser.ast.DataValue;
import io.ltr8.tson.parser.ast.EmptyBrace;
import io.ltr8.tson.parser.ast.RecordValue;
import io.ltr8.tson.parser.ast.ScopedValue;
import io.ltr8.tson.parser.ast.TokenForm;
import io.ltr8.tson.parser.ast.TokenValue;
import io.ltr8.tson.parser.ast.schema.SchemaMap;
import io.ltr8.tson.schema.meta.FieldState;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Token;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeRef;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link PositionalForm#normalizeToRecordForm} on its own, isolated from {@code Instance}/{@code
 * DefinitionResolver} wiring -- exercises both of its jobs against hand-built {@link RecordBody}
 * values: the positional-form field-name lookup (§5.6's "exactly one bare REQUIRED field" rule)
 * and the wrap-vs-pass-through decision, then schema-composed default-filling (§5.2/§5.7's {@code
 * ~}/{@code =} field modifiers, added once {@code float32}/{@code float64} surfaced a real gap --
 * see this class's own Javadoc). Also confirms both against real resolved shapes: {@code enum}'s
 * own ({@code enum => ~atom & { members: set<token> }}) for wrapping, and a hand-built mirror of
 * {@code float_type}'s real shape for defaulting (the real end-to-end case lives in
 * {@code DefinitionResolverTest.resolvesFloat32AndFloat64FromTheRealCoreTypeLibraryFixture}).
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

    // ── Schema-composed defaults (REQUIRED_DEFAULT / REQUIRED_FIXED) ───────

    private static RecordField requiredDefault(String name, String tokenText) {
        return new RecordField(name, TypeRef.of("token"), FieldState.REQUIRED_DEFAULT,
                Optional.of(new Token(tokenText, Token.Form.UNQUOTED)), Optional.empty());
    }

    private static RecordField requiredFixed(String name, String tokenText) {
        return new RecordField(name, TypeRef.of("token"), FieldState.REQUIRED_FIXED,
                Optional.of(new Token(tokenText, Token.Form.UNQUOTED)), Optional.empty());
    }

    @Test
    void fillsInAMissingRequiredDefaultField() {
        RecordBody body = RecordBody.of(List.of(
                RecordField.required("format", TypeRef.of("token")), requiredDefault("allow_nan", "true")));
        DataValue value = new DataValue(List.of(), Optional.empty(),
                new RecordValue(List.of(new RecordValue.Field("format",
                        new ScopedValue(Optional.empty(), bareToken("BINARY32"))))));

        DataValue result = PositionalForm.normalizeToRecordForm(value, body);

        RecordValue record = (RecordValue) result.coreValue();
        assertEquals(2, record.fields().size());
        assertEquals("format", record.fields().get(0).name());
        assertEquals("allow_nan", record.fields().get(1).name());
        assertEquals(new TokenValue("true", TokenForm.UNQUOTED),
                record.fields().get(1).value().value().coreValue());
    }

    @Test
    void fillsInAMissingRequiredFixedField() {
        RecordBody body = RecordBody.of(List.of(requiredFixed("spec", "https://example.com/rfc")));
        DataValue value = new DataValue(List.of(), Optional.empty(), new EmptyBrace());

        DataValue result = PositionalForm.normalizeToRecordForm(value, body);

        RecordValue record = (RecordValue) result.coreValue();
        assertEquals(1, record.fields().size());
        assertEquals("spec", record.fields().get(0).name());
        assertEquals(new TokenValue("https://example.com/rfc", TokenForm.UNQUOTED),
                record.fields().get(0).value().value().coreValue());
    }

    @Test
    void doesNotOverrideAFieldTheInstanceAlreadySpecifies() {
        RecordBody body = RecordBody.of(List.of(requiredDefault("allow_nan", "true")));
        DataValue value = new DataValue(List.of(), Optional.empty(),
                new RecordValue(List.of(new RecordValue.Field("allow_nan",
                        new ScopedValue(Optional.empty(), bareToken("false"))))));

        DataValue result = PositionalForm.normalizeToRecordForm(value, body);

        // An instance is free to override its own default -- the schema's own "true" must not
        // clobber the explicit "false" actually written.
        RecordValue record = (RecordValue) result.coreValue();
        assertEquals(1, record.fields().size());
        assertEquals(new TokenValue("false", TokenForm.UNQUOTED), record.fields().get(0).value().value().coreValue());
    }

    @Test
    void leavesAParameterRoutedDefaultFieldAbsentRatherThanGuessing() {
        // A defaulted field routed through a type parameter (value_param, §5.10) has no literal
        // Token to fill in with here -- left absent, not an error; a template's own value parameter
        // is settled at application time, which this resolver doesn't have yet.
        RecordField parameterRouted = new RecordField("size", TypeRef.of("integer_size"),
                FieldState.REQUIRED_DEFAULT, Optional.empty(), Optional.of("N"));
        RecordBody body = RecordBody.of(List.of(parameterRouted));
        DataValue value = new DataValue(List.of(), Optional.empty(), new EmptyBrace());

        DataValue result = PositionalForm.normalizeToRecordForm(value, body);

        assertSame(value, result);
    }

    @Test
    void fillsMultipleDefaultsAgainstAnAlreadyRecordShapedValueLikeTheRealFloat32Case() {
        // Mirrors meta.tn1's real float_type shape: float32 => !float_type { format: BINARY32 }
        // never mentions allow_nan/allow_infinity/allow_subnormal/allow_negative_zero (all
        // `boolean ~ true`) -- this value never touches the wrapping step at all (it already
        // arrives as a one-field RecordValue), so defaulting must run on the pass-through path too.
        RecordBody body = RecordBody.of(List.of(
                RecordField.required("format", TypeRef.of("token")),
                requiredDefault("allow_nan", "true"),
                requiredDefault("allow_infinity", "true"),
                requiredDefault("allow_subnormal", "true"),
                requiredDefault("allow_negative_zero", "true")));
        DataValue value = new DataValue(List.of(), Optional.of("float_type"),
                new RecordValue(List.of(new RecordValue.Field("format",
                        new ScopedValue(Optional.empty(), bareToken("BINARY32"))))));

        DataValue result = PositionalForm.normalizeToRecordForm(value, body);

        RecordValue record = (RecordValue) result.coreValue();
        assertEquals(5, record.fields().size());
        List<String> names = record.fields().stream().map(RecordValue.Field::name).toList();
        assertEquals(List.of("format", "allow_nan", "allow_infinity", "allow_subnormal", "allow_negative_zero"), names);
    }

    // ── Against enum's own real resolved shape ─────────────────────────────

    @Test
    void wrapsAnEnumInstanceValueUsingEnumsOwnRealResolvedBody() {
        SchemaMap schemaMap = new TsonSchemaParser("""
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn1"
                {
                  top => {}
                  atom => top & {}
                  enum => ~atom & { members: set<token> }
                }""").parseSchemaDocument().body();
        Map<String, TypeDefinition> resolved = new LinkedHashMap<>();
        DefinitionResolver resolver = new DefinitionResolver((type, value) -> {
            throw new UnsupportedOperationException("not exercised by this test");
        }, name -> null, resolved::get);
        resolved.put("top", resolver.resolve(schemaMap.declarations().get("top")));
        resolved.put("atom", resolver.resolve(schemaMap.declarations().get("atom")));
        TypeDefinition enumDef = resolver.resolve(schemaMap.declarations().get("enum"));
        RecordBody enumBody = (RecordBody) enumDef.body();

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
