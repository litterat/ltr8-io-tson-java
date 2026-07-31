package io.ltr8.tson.compiler;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Class 1 (schemaless) validation: base syntax plus built-in / core-vocabulary typed atoms, with a
 * non-built-in type-ref reported as unknown (no schema in scope to define it).
 */
class SchemalessValidatorTest {

    @Test
    void aWellFormedDocumentWithGoodBuiltinAtomsHasNoDiagnostics() {
        List<Diagnostic> diagnostics = SchemalessValidator.validate("""
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
        assertEquals(List.of(), SchemalessValidator.validate("{ a: 1  b: hello  c: [x y z]  d: 3.14 }"));
    }

    @Test
    void aBadBuiltinAtomIsAnAtomConstraintViolationWithAPath() {
        List<Diagnostic> diagnostics = SchemalessValidator.validate("{ id: !uuid nope  count: !int32 twelve }");
        assertEquals(2, diagnostics.size(), diagnostics.toString());
        assertTrue(diagnostics.stream().allMatch(d -> d.code() == Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION), diagnostics.toString());
        assertTrue(diagnostics.stream().anyMatch(d -> d.path().equals("/id")), diagnostics.toString());
        assertTrue(diagnostics.stream().anyMatch(d -> d.path().equals("/count")), diagnostics.toString());
        assertTrue(diagnostics.stream().allMatch(d -> d.dataPosition().isPresent()), diagnostics.toString());
    }

    @Test
    void anOutOfRangeIntegerIsReported() {
        List<Diagnostic> diagnostics = SchemalessValidator.validate("{ big: !int32 99999999999999 }");
        assertEquals(1, diagnostics.size(), diagnostics.toString());
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, diagnostics.getFirst().code());
        assertEquals("/big", diagnostics.getFirst().path());
    }

    @Test
    void anUnknownTypeRefIsReportedSinceNoSchemaDefinesIt() {
        List<Diagnostic> diagnostics = SchemalessValidator.validate("!widget { x: 1 }");
        assertEquals(1, diagnostics.size(), diagnostics.toString());
        assertEquals(Diagnostic.Code.UNKNOWN_TYPE_REF, diagnostics.getFirst().code());
    }

    @Test
    void nestedBadAtomsInArraysAndMapsAreReportedWithTheirPaths() {
        List<Diagnostic> diagnostics = SchemalessValidator.validate("{ ids: [ !uuid ok-nope  !uuid also-bad ] }");
        assertEquals(2, diagnostics.size(), diagnostics.toString());
        assertTrue(diagnostics.stream().anyMatch(d -> d.path().equals("/ids/0")), diagnostics.toString());
        assertTrue(diagnostics.stream().anyMatch(d -> d.path().equals("/ids/1")), diagnostics.toString());
    }

    @Test
    void aBaseSyntaxErrorIsASingleDiagnostic() {
        List<Diagnostic> diagnostics = SchemalessValidator.validate("{ a: 1 ");   // unclosed record
        assertEquals(1, diagnostics.size(), diagnostics.toString());
        assertEquals(Diagnostic.Code.VALIDATION_ERROR, diagnostics.getFirst().code());
    }
}
