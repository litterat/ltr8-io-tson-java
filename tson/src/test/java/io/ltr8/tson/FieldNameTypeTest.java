package io.ltr8.tson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ltr8.tson.base.Diagnostic;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A record's field names have a type (SPEC-FEEDBACK.md #10). The grammar admits any single-line token in name
 * position; a record judges its names by its field name type, an identifier unless it says otherwise.
 */
class FieldNameTypeTest {

    private static Diagnostic only(List<Diagnostic> problems) {
        assertEquals(1, problems.size(), problems.toString());
        return problems.getFirst();
    }

    /** The default does not change: a schemaless record's field names are identifiers, now a resolver error. */
    @Test
    void aSchemalessFieldNameThatIsNoIdentifierIsAResolverError() {
        Diagnostic problem = only(Tson.standard().validate("{ \"first name\": 1 }"));
        assertEquals(Diagnostic.Code.ATOM_FORM_INVALID, problem.code());
        assertTrue(problem.message().contains("belongs in a map"), problem.message());
        assertEquals(Diagnostic.Code.ATOM_FORM_INVALID, only(Tson.standard().validate("{ \"@id\": 1 }")).code());
    }

    @Test
    void aQuotedIdentifierIsStillAFieldName() {
        assertEquals(List.of(), Tson.standard().validate("{ \"order-id\": 1  \"true\": 2 }"));
    }

    @Test
    void annotationNamesStayIdentifiersByTheGrammar() {
        assertEquals(1, Tson.standard().validate("{ a: @\"x y\" 1 }").size());
    }
}
