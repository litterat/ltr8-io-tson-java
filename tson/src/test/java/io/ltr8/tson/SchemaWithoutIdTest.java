package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.SchemaValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A schema document with no {@code !!id}. The directive is optional in the grammar ([TSON-DATA] §2.2) and
 * required of a schema registered under its own name (§2.2.1), which is what both front doors do, so a document
 * without one parses and is then refused as the author's error: {@link Tson#validateSchema} reports it as a
 * {@code SCHEMA_ERROR} against the document as a whole, honouring its promise never to throw for a bad input
 * document, and the fail-fast {@link Tson#resolve} throws {@link SchemaValidationException}, never a fault.
 */
class SchemaWithoutIdTest {

    private static final String NO_ID = """
            !!meta:"https://tson.io/2026/39/m/meta.tn"
            !!import:"https://tson.io/2026/39/m/core.tn"
            {
              point => { x: int32  y: int32 }
            }
            """;

    @Test
    void validateSchemaReportsTheMissingIdAsASchemaError() {
        List<Diagnostic> problems = Tson.standard().validateSchema(NO_ID);
        assertEquals(1, problems.size(), problems::toString);
        Diagnostic problem = problems.getFirst();
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, problem.code(), problem::toString);
        assertEquals(Optional.of(""), problem.schemaPointer(), problem::toString);
        assertTrue(problem.message().contains("!!id is required"), problem::toString);
    }

    @Test
    void resolveRefusesTheMissingIdAsTheAuthorsError() {
        SchemaValidationException e = assertThrows(SchemaValidationException.class,
                () -> Tson.standard().resolve(NO_ID));
        assertTrue(e.getMessage().contains("!!id is required"), e::getMessage);
    }
}
