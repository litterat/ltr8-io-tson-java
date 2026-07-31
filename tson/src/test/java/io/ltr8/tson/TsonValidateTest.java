package io.ltr8.tson;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonSchemaSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Tson#validate} works out on its own whether a data document's {@code !!schema} selects a
 * schema (resolved through the configured {@link TsonSchemaSource}, type from the root type-ref) or
 * whether it's validated schemalessly, returning every problem as a {@link Diagnostic} (empty == valid).
 */
class TsonValidateTest {

    private static final String POINT_ID = "https://example.test/point-1.tn";
    private static final String POINT_SCHEMA = """
            !!id:"https://example.test/point-1.tn"
            !!meta:"https://tson.io/2026/32/m/meta.tn"
            !!import:"https://tson.io/2026/32/m/core.tn"
            { point => { x: int32  y: int32 } }
            """;

    private static Tson tsonWithPoint() {
        TsonSchemaSource source = uri -> {
            if (uri.equals(POINT_ID)) {
                return POINT_SCHEMA;
            }
            throw new IllegalStateException("no schema for " + uri);
        };
        return Tson.builder().schemaSource(source).build();
    }

    @Test
    void selfDescribingDataResolvesItsSchemaAndValidates() {
        List<Diagnostic> problems = tsonWithPoint().validate("""
                !!schema:"https://example.test/point-1.tn"
                !point { x: 3  y: 4 }""");
        assertEquals(List.of(), problems);
    }

    @Test
    void aBadValueInSchemaDrivenDataIsReported() {
        List<Diagnostic> problems = tsonWithPoint().validate("""
                !!schema:"https://example.test/point-1.tn"
                !point { x: 3  y: 99999999999999 }""");
        assertEquals(1, problems.size(), problems.toString());
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, problems.getFirst().code());
    }

    @Test
    void aSchemaTheSourceCannotProvideIsASchemaError() {
        List<Diagnostic> problems = tsonWithPoint().validate("""
                !!schema:"https://example.test/not-there.tn"
                !point { x: 3  y: 4 }""");
        assertEquals(1, problems.size(), problems.toString());
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, problems.getFirst().code());
    }

    @Test
    void aSchemaDrivenDocumentWithNoRootTypeRefIsReported() {
        List<Diagnostic> problems = tsonWithPoint().validate("""
                !!schema:"https://example.test/point-1.tn"
                { x: 3  y: 4 }""");
        assertEquals(1, problems.size(), problems.toString());
        assertEquals(Diagnostic.Code.VALIDATION_ERROR, problems.getFirst().code());
        assertTrue(problems.getFirst().message().contains("root type-ref"), problems.toString());
    }

    @Test
    void aRootTypeRefTheSchemaDoesNotDeclareIsAnUnknownType() {
        List<Diagnostic> problems = tsonWithPoint().validate("""
                !!schema:"https://example.test/point-1.tn"
                !no_such_type { x: 3  y: 4 }""");
        assertEquals(1, problems.size(), problems.toString());
        assertEquals(Diagnostic.Code.UNKNOWN_TYPE, problems.getFirst().code());
    }

    @Test
    void dataWithNoSchemaIsValidatedSchemalessly() {
        Tson tson = tsonWithPoint();
        assertEquals(List.of(), tson.validate("{ id: !uuid 9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09  n: !int32 5 }"));

        List<Diagnostic> problems = tson.validate("{ n: !int32 twelve }");
        assertEquals(1, problems.size(), problems.toString());
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, problems.getFirst().code());
    }
}
