package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TestDocuments;
import io.ltr8.tson.compiler.TsonCompiledSchema;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonDiagnosticsCollector;
import io.ltr8.tson.compiler.TsonDiagnosticsReceiver;
import io.ltr8.tson.compiler.TsonReadException;
import io.ltr8.tson.compiler.TsonSchemaCompiler;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.FieldState;
import io.ltr8.tson.schema.meta.IntegerSize;
import io.ltr8.tson.schema.meta.IntegerType;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Token;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof of the compiled-schema-reader sketch against the real {@link RecordTreeReader} and
 * {@link AtomTypeReader#INTEGER_TYPE} -- a real (hand-built, but shaped exactly like a materialized
 * {@link TsonSchema} would be) schema compiled with the real DOM-mode factory registry, read against
 * real TSON data source text through the real compiled reader.
 */
class RecordTreeReaderTest {

    private static TsonLinkedSchema pointSchema(TypeDefinition integerEntry, RecordField valueField) {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("integer", integerEntry);
        entries.put("point", TypeDefinition.product(RecordBody.of(List.of(valueField))));
        return new TsonLinkedSchema(new TsonSchema("test-schema", "test-meta", List.of(), entries));
    }

    private static TypeDefinition atomEntry(IntegerType body) {
        return new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), false, List.of(), List.of(),
                Optional.empty(), body);
    }

    private static TsonCompiledSchema compile(TsonLinkedSchema linkedSchema) {
        return TsonSchemaCompiler.compile(linkedSchema, ValueReaderFactoryRegistry.tree());
    }

    private static Map<String, Object> read(TsonCompiledSchema compiled, String source) {
        return read(compiled, source, TsonDiagnosticsReceiver.throwing());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(TsonCompiledSchema compiled, String source,
                                            TsonDiagnosticsReceiver receiver) {
        return (Map<String, Object>) Dom.of((TsonValue) compiled.get("point")
                .read(TestDocuments.document(source, receiver)));
    }

    @Test
    void unconstrainedIntegerFieldReadsFromRealDataText() {
        TsonCompiledSchema compiled = compile(
                pointSchema(atomEntry(IntegerType.UNCONSTRAINED), RecordField.required("value", TypeRef.of("integer"))));

        assertEquals(BigInteger.valueOf(42), read(compiled, "{ value: 42 }").get("value"));
    }

    @Test
    void constrainedIntegerFieldValidatesAgainstItsOwnDeclaredRange() {
        TsonCompiledSchema compiled = compile(
                pointSchema(atomEntry(new IntegerType(new IntegerSize(8, true))),
                        RecordField.required("value", TypeRef.of("integer"))));

        assertEquals((byte) 100, read(compiled, "{ value: 100 }").get("value"));
        assertThrows(TsonReadException.class, () -> read(compiled, "{ value: 200 }"));
    }

    @Test
    void missingRequiredFieldThrows() {
        TsonCompiledSchema compiled = compile(
                pointSchema(atomEntry(IntegerType.UNCONSTRAINED), RecordField.required("value", TypeRef.of("integer"))));

        TsonReadException thrown = assertThrows(TsonReadException.class, () -> read(compiled, "{}"));
        assertTrue(thrown.getMessage().contains("value"), thrown.getMessage());
    }

    @Test
    void absentOptionalFieldReadsAsNull() {
        RecordField optional = new RecordField("value", TypeRef.of("integer"), FieldState.OPTIONAL,
                Optional.empty(), Optional.empty());
        TsonCompiledSchema compiled = compile(pointSchema(atomEntry(IntegerType.UNCONSTRAINED), optional));

        assertNull(read(compiled, "{}").get("value"));
        assertNull(read(compiled, "{ value: _ }").get("value"));
    }

    private static RecordField fixed(FieldState state, String token) {
        return new RecordField("value", TypeRef.of("integer"), state,
                token == null ? Optional.empty() : Optional.of(new Token(token, Token.Form.UNQUOTED)),
                Optional.empty());
    }

    /**
     * §5.2: a REQUIRED_FIXED field "may be provided with a value matching the fixed value, or omitted (the
     * fixed value is used)". Both routes land on the schema's value -- the document's own token is never the
     * source, only a claim to be checked.
     */
    @Test
    void requiredFixedFieldInjectsWhenAbsentAndAcceptsAMatchingValue() {
        TsonCompiledSchema compiled = compile(pointSchema(atomEntry(IntegerType.UNCONSTRAINED),
                fixed(FieldState.REQUIRED_FIXED, "7")));

        assertEquals(BigInteger.valueOf(7), read(compiled, "{}").get("value"));
        assertEquals(BigInteger.valueOf(7), read(compiled, "{ value: 7 }").get("value"));
    }

    /**
     * §5.2: "A contradicting value is a validation error." The reader used to skip a written fixed field
     * unread, so the document said 9, the reader returned 7, and nothing was reported -- a decoded document
     * that differs from its own bytes in silence.
     */
    @Test
    void requiredFixedFieldRejectsAContradictingValue() {
        TsonCompiledSchema compiled = compile(pointSchema(atomEntry(IntegerType.UNCONSTRAINED),
                fixed(FieldState.REQUIRED_FIXED, "7")));

        TsonReadException thrown = assertThrows(TsonReadException.class, () -> read(compiled, "{ value: 9 }"));
        assertTrue(thrown.getMessage().contains("cannot be given another value"), thrown.getMessage());
    }

    /**
     * A token that isn't a value of the field's own type is one problem, not two. Decoding it reports (here:
     * 300 doesn't fit int8), and the contradiction check that follows would otherwise report again at the
     * same path, on the strength of whatever the failed decode handed back.
     */
    @Test
    void aFixedFieldWhoseStatedTokenIsMalformedReportsOnce() {
        TsonCompiledSchema compiled = compile(pointSchema(atomEntry(new IntegerType(new IntegerSize(8, true))),
                fixed(FieldState.REQUIRED_FIXED, "7")));
        TsonDiagnosticsCollector problems = TsonDiagnosticsReceiver.collecting();

        compiled.get("point").read(TestDocuments.document("{ value: 300 }", problems));

        assertEquals(1, problems.diagnostics().size(), problems.diagnostics().toString());
        assertTrue(problems.diagnostics().getFirst().message().contains("300"),
                problems.diagnostics().toString());
    }

    /**
     * The other half of the same rule: a token that decodes cleanly and merely says something else is still a
     * contradiction, and is still reported -- exactly once, so the skip above doesn't swallow the real check.
     */
    @Test
    void aWellFormedContradictingTokenStillReportsOnce() {
        TsonCompiledSchema compiled = compile(pointSchema(atomEntry(IntegerType.UNCONSTRAINED),
                fixed(FieldState.REQUIRED_FIXED, "7")));
        TsonDiagnosticsCollector problems = TsonDiagnosticsReceiver.collecting();

        compiled.get("point").read(TestDocuments.document("{ value: 9 }", problems));

        assertEquals(1, problems.diagnostics().size(), problems.diagnostics().toString());
        assertTrue(problems.diagnostics().getFirst().message().contains("cannot be given another value"),
                problems.diagnostics().toString());
    }

    /**
     * The code is {@code FIELD_FIXED}, not {@code ATOM_CONSTRAINT_VIOLATION}: nothing about {@code 9}
     * failed {@code integer}'s grammar or any facet of it, so a consumer routing on {@code code} must be
     * able to tell a §5.2 field-state rule from an atom's own contract. All three ways to break a FIXED
     * field report the same code.
     */
    @Test
    void everyFixedViolationReportsFieldFixedRatherThanAnAtomConstraint() {
        TsonCompiledSchema required = compile(pointSchema(atomEntry(IntegerType.UNCONSTRAINED),
                fixed(FieldState.REQUIRED_FIXED, "7")));
        TsonCompiledSchema fixedAbsent = compile(pointSchema(atomEntry(IntegerType.UNCONSTRAINED),
                fixed(FieldState.OPTIONAL_FIXED, null)));

        assertEquals(Diagnostic.Code.FIELD_FIXED, onlyDiagnostic(required, "{ value: 9 }").code());
        assertEquals(Diagnostic.Code.FIELD_FIXED, onlyDiagnostic(required, "{ value: _ }").code());
        assertEquals(Diagnostic.Code.FIELD_FIXED, onlyDiagnostic(fixedAbsent, "{ value: 7 }").code());
    }

    /**
     * {@code =} reads as "default" to anyone arriving from JSON Schema, so {@code priority: priority =
     * medium} is a plausible mis-spelling of {@code ~ medium} -- and the author otherwise discovers it only
     * by watching the reader reject every document that dared to differ. The message names the fix.
     */
    @Test
    void aContradictedFixedValuePointsTheAuthorAtTheDefaultSpelling() {
        TsonCompiledSchema compiled = compile(pointSchema(atomEntry(IntegerType.UNCONSTRAINED),
                fixed(FieldState.REQUIRED_FIXED, "7")));

        String message = onlyDiagnostic(compiled, "{ value: 9 }").message();

        assertTrue(message.contains("declares it with '=' (fixed)"), message);
        assertTrue(message.contains("use '~'"), message);
    }

    /**
     * {@code expected}/{@code actual} exist so a consumer reads the two values without parsing the message.
     * In tree mode a decoded value is a {@code TsonAtom}, so this is where the record's own default
     * rendering would land in the structured half of the diagnostic.
     */
    @Test
    void aFixedViolationNamesBothValuesRatherThanTheTreeNodesComponents() {
        TsonCompiledSchema compiled = compile(pointSchema(atomEntry(IntegerType.UNCONSTRAINED),
                fixed(FieldState.REQUIRED_FIXED, "7")));

        Diagnostic reported = onlyDiagnostic(compiled, "{ value: 9 }");

        assertEquals("7", reported.expected());
        assertEquals("9", reported.actual());
    }

    private static Diagnostic onlyDiagnostic(TsonCompiledSchema compiled, String document) {
        TsonDiagnosticsCollector problems = TsonDiagnosticsReceiver.collecting();
        compiled.get("point").read(TestDocuments.document(document, problems));
        assertEquals(1, problems.diagnostics().size(), problems.diagnostics().toString());
        return problems.diagnostics().getFirst();
    }

    /** §5.2: "At a plain REQUIRED or a REQUIRED_FIXED field, `_` is a validation error." */
    @Test
    void requiredFixedFieldRejectsTheAbsentSentinel() {
        TsonCompiledSchema compiled = compile(pointSchema(atomEntry(IntegerType.UNCONSTRAINED),
                fixed(FieldState.REQUIRED_FIXED, "7")));

        TsonReadException thrown = assertThrows(TsonReadException.class, () -> read(compiled, "{ value: _ }"));
        assertTrue(thrown.getMessage().contains("cannot be absent"), thrown.getMessage());
    }

    /**
     * The one observable difference between the two FIXED states, and the reason both exist: §5.2's
     * injection rule names REQUIRED_DEFAULT and REQUIRED_FIXED and <em>not</em> OPTIONAL_FIXED, so an
     * omitted OPTIONAL_FIXED field stays absent instead of materialising a value the document never wrote.
     * Reading it as injected made the two states indistinguishable and the {@code ?} decide nothing
     * ({@code spec/tson-rev33-changelog.md} #39 asks the spec to say this outright).
     */
    @Test
    void optionalFixedFieldStaysAbsentWhenOmittedButIsPresentWhenWritten() {
        TsonCompiledSchema compiled = compile(pointSchema(atomEntry(IntegerType.UNCONSTRAINED),
                fixed(FieldState.OPTIONAL_FIXED, "7")));

        assertNull(read(compiled, "{}").get("value"));
        assertNull(read(compiled, "{ value: _ }").get("value")); // optional: absence is what it permits
        assertEquals(BigInteger.valueOf(7), read(compiled, "{ value: 7 }").get("value"));
    }

    /** Optional does not mean unconstrained: if the field is there at all, it must carry the fixed value. */
    @Test
    void optionalFixedFieldRejectsAContradictingValue() {
        TsonCompiledSchema compiled = compile(pointSchema(atomEntry(IntegerType.UNCONSTRAINED),
                fixed(FieldState.OPTIONAL_FIXED, "7")));

        TsonReadException thrown = assertThrows(TsonReadException.class, () -> read(compiled, "{ value: 9 }"));
        assertTrue(thrown.getMessage().contains("cannot be given another value"), thrown.getMessage());
    }

    /**
     * §5.2's sixth spelling, {@code field: type? = _}: OPTIONAL_FIXED carrying no value at all, so "the field
     * MUST either be omitted or be the absent sentinel in conforming data; any other value is a validation
     * error". There is nothing to inject and nothing to compare against -- only presence is checked.
     */
    @Test
    void optionalFixedWithNoValueAdmitsOnlyOmissionOrTheAbsentSentinel() {
        TsonCompiledSchema compiled = compile(pointSchema(atomEntry(IntegerType.UNCONSTRAINED),
                fixed(FieldState.OPTIONAL_FIXED, null)));

        assertNull(read(compiled, "{}").get("value"));
        assertNull(read(compiled, "{ value: _ }").get("value"));

        TsonReadException thrown = assertThrows(TsonReadException.class, () -> read(compiled, "{ value: 7 }"));
        assertTrue(thrown.getMessage().contains("fixed to absent"), thrown.getMessage());
    }

    @Test
    void requiredDefaultFieldFillsFromTheSchemaWhenAbsentButExplicitValueStillWins() {
        RecordField defaulted = new RecordField("value", TypeRef.of("integer"), FieldState.REQUIRED_DEFAULT,
                Optional.of(new Token("7", Token.Form.UNQUOTED)), Optional.empty());
        TsonCompiledSchema compiled = compile(pointSchema(atomEntry(IntegerType.UNCONSTRAINED), defaulted));

        assertEquals(BigInteger.valueOf(7), read(compiled, "{}").get("value"));
        assertEquals(BigInteger.valueOf(9), read(compiled, "{ value: 9 }").get("value"));
    }

    /**
     * Omission and a written {@code _} are two different documents at a REQUIRED_DEFAULT field. §5.2 asks
     * the decoder to warn and inject for the second; {@code spec/tson-rev33-changelog.md} #42 calls that its strongest
     * case for an error, since warn-and-inject answers "here is a value" to a document that said "absent".
     * The default is still what the field decodes to -- only the verdict changes.
     */
    @Test
    void requiredDefaultFieldRejectsAWrittenAbsentSentinelWhileOmissionStillInjects() {
        RecordField defaulted = new RecordField("value", TypeRef.of("integer"), FieldState.REQUIRED_DEFAULT,
                Optional.of(new Token("7", Token.Form.UNQUOTED)), Optional.empty());
        TsonCompiledSchema compiled = compile(pointSchema(atomEntry(IntegerType.UNCONSTRAINED), defaulted));

        assertEquals(BigInteger.valueOf(7), read(compiled, "{}").get("value"));

        TsonReadException thrown = assertThrows(TsonReadException.class, () -> read(compiled, "{ value: _ }"));
        assertTrue(thrown.getMessage().contains("cannot be written '_'"), thrown.getMessage());

        TsonDiagnosticsCollector problems = new TsonDiagnosticsCollector();
        assertEquals(BigInteger.valueOf(7), read(compiled, "{ value: _ }", problems).get("value"));
        assertEquals(1, problems.diagnostics().size(), problems.diagnostics().toString());
    }
}
