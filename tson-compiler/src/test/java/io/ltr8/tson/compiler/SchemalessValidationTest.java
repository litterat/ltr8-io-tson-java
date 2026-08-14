package io.ltr8.tson.compiler;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Class 1 (schemaless) validation: base syntax plus the built-in type vocabulary, with a type-ref naming
 * nothing built-in reported as unknown (no schema in scope to define it).
 *
 * <p>Validating is a {@link TsonTreeReader} read with a collecting receiver and the tree discarded -- {@link
 * #validate} below is {@code Tson.validate}'s whole body, minus the schema environment a Class 1 document
 * doesn't need. This test is that promise: the codes, paths and positions a caller renders come out of the
 * reader, not out of a second implementation kept in step by hand.
 */
class SchemalessValidationTest {

    private static final TsonTreeReader READER = new TsonTreeReader();

    /** {@code Tson.validate}'s body: collect, and convert the one class of failure that can't be collected. */
    private static List<Diagnostic> validate(String source) {
        TsonDiagnosticsCollector problems = TsonDiagnosticsReceiver.collecting();
        try {
            READER.withDiagnostics(problems).read(source);
        } catch (RuntimeException e) {
            return List.of(Diagnostic.ofBaseSyntaxError(e));
        }
        return problems.diagnostics();
    }

    @Test
    void aWellFormedDocumentWithGoodBuiltinAtomsHasNoDiagnostics() {
        List<Diagnostic> diagnostics = validate("""
                {
                    id: !uuid 9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09
                    count: !int32 5
                    tags: [a b "c"]
                    when: !date 2026-01-15
                }""");
        assertEquals(List.of(), diagnostics);
    }

    @Test
    void anUntypedDocumentIsAlwaysValid() {
        // Every untyped token resolves under §4 (null/boolean/number/string), so base resolution alone
        // can never fail.
        assertEquals(List.of(), validate("{ a: 1  b: hello  c: [x y z]  d: 3.14 }"));
    }

    @Test
    void aBadBuiltinAtomIsAnAtomConstraintViolationWithAPath() {
        List<Diagnostic> diagnostics = validate("{ id: !uuid nope  count: !int32 twelve }");
        assertEquals(2, diagnostics.size(), diagnostics.toString());
        assertTrue(diagnostics.stream().allMatch(d -> d.code() == Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION), diagnostics.toString());
        assertTrue(diagnostics.stream().anyMatch(d -> d.path().equals("/id")), diagnostics.toString());
        assertTrue(diagnostics.stream().anyMatch(d -> d.path().equals("/count")), diagnostics.toString());
        assertTrue(diagnostics.stream().allMatch(d -> d.dataPosition().isPresent()), diagnostics.toString());
    }

    @Test
    void anOutOfRangeIntegerIsReported() {
        List<Diagnostic> diagnostics = validate("{ big: !int32 99999999999999 }");
        assertEquals(1, diagnostics.size(), diagnostics.toString());
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, diagnostics.getFirst().code());
        assertEquals("/big", diagnostics.getFirst().path());
    }

    @Test
    void anUnknownTypeRefIsReportedSinceNoSchemaDefinesIt() {
        List<Diagnostic> diagnostics = validate("!widget { x: 1 }");
        assertEquals(1, diagnostics.size(), diagnostics.toString());
        assertEquals(Diagnostic.Code.UNKNOWN_TYPE_REF, diagnostics.getFirst().code());
    }

    /** A built-in name is scalar-only, so one on a container is a mismatch rather than an unknown name. */
    @Test
    void aBuiltinTypeRefOnAContainerIsReported() {
        List<Diagnostic> diagnostics = validate("!uuid { x: 1 }");
        assertEquals(1, diagnostics.size(), diagnostics.toString());
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, diagnostics.getFirst().code());
    }

    @Test
    void nestedBadAtomsInArraysAndMapsAreReportedWithTheirPaths() {
        List<Diagnostic> diagnostics = validate("{ ids: [ !uuid ok-nope  !uuid also-bad ] }");
        assertEquals(2, diagnostics.size(), diagnostics.toString());
        assertTrue(diagnostics.stream().anyMatch(d -> d.path().equals("/ids/0")), diagnostics.toString());
        assertTrue(diagnostics.stream().anyMatch(d -> d.path().equals("/ids/1")), diagnostics.toString());
    }

    /** New coverage the AST walk never had: an annotation's value is a data-value (§3.1), so it is checked too. */
    @Test
    void aBadAtomInsideAnAnnotationValueIsReported() {
        List<Diagnostic> diagnostics = validate("{ a: @since:!date nope 1 }");
        assertEquals(1, diagnostics.size(), diagnostics.toString());
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, diagnostics.getFirst().code());
    }

    @Test
    void aBaseSyntaxErrorIsASingleDiagnostic() {
        List<Diagnostic> diagnostics = validate("{ a: 1 ");   // unclosed record
        assertEquals(1, diagnostics.size(), diagnostics.toString());
        assertEquals(Diagnostic.Code.VALIDATION_ERROR, diagnostics.getFirst().code());
    }

    /**
     * A fault that isn't a base-syntax failure rethrows rather than becoming a diagnostic -- validating
     * promises not to throw for a bad <em>document</em>, and reporting a library bug as "your document is
     * invalid" would bury the real failure behind a false verdict.
     */
    @Test
    void anExceptionThatIsNotABaseSyntaxFailureIsRethrown() {
        IllegalStateException fault = new IllegalStateException("a bug, not a bad document");
        assertSame(fault, assertThrows(IllegalStateException.class, () -> Diagnostic.ofBaseSyntaxError(fault)));
    }

    /** §8.1 wants a schema document handed in where data was expected to be a categorized diagnostic, not "malformed". */
    @Test
    void aSchemaDocumentWhereDataWasExpectedIsADiagnostic() {
        List<Diagnostic> diagnostics = validate("""
                !!id:"https://example.test/s-1.tn"
                !!meta:"https://tson.io/2026/32/m/meta.tn"
                point => { x: int32 }""");
        assertEquals(1, diagnostics.size(), diagnostics.toString());
        assertEquals(Diagnostic.Code.VALIDATION_ERROR, diagnostics.getFirst().code());
    }
}
