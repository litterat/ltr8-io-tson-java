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

    private static final String HEADER = """
            !!id:"https://example.test/%s.tn"
            !!meta:"https://tson.io/2026/38/m/meta.tn"
            !!import:"https://tson.io/2026/38/m/core.tn"
            """;

    private static String schema(String id, String body) {
        return HEADER.formatted(id) + "{\n" + body + "\n}\n";
    }

    /** A record states its field names' family; a JSON-LD translation's admits a leading @ or $. */
    static final String JSON_LD = schema("json-ld", """
              json_name => !identifier_type { start_add: "@$" }
              node => !record {
                field_name_type: json_name
                fields: [
                  { name: "@id"  type: text }
                  { name: "$ref"  type: text  optional: true }
                  { name: label  type: text }
                ]
              }
            """);

    @Test
    void aRecordMayStateARelaxedFieldNameType() {
        Tson tson = Tson.standard();
        tson.resolve(JSON_LD);
    }

    @Test
    void aTextFieldNameTypeAdmitsAnyText() {
        Tson.standard().resolve(schema("texts", """
              row => !record { field_name_type: text  fields: [ { name: "first name"  type: text } ] }
            """));
    }

    /** The default does not change under a schema either: an identifier, refused at schema load. */
    @Test
    void aNameOutsideTheRecordsTypeIsRefusedAtSchemaLoad() {
        var thrown = org.junit.jupiter.api.Assertions.assertThrows(io.ltr8.tson.base.SchemaValidationException.class,
                () -> Tson.standard().resolve(schema("default", """
                      node => !record { fields: [ { name: "@id"  type: text } ] }
                    """)));
        assertTrue(thrown.getMessage().contains("field_name_type 'field_name'"), thrown.getMessage());
        org.junit.jupiter.api.Assertions.assertThrows(io.ltr8.tson.base.SchemaValidationException.class,
                () -> Tson.standard().resolve(schema("number", """
                      node => !record { field_name_type: int32  fields: [ { name: "1"  type: text } ] }
                    """)));
    }

    /** A composition absorbs its supertypes' names, so it takes their field_name_type; a refinement keeps its own. */
    @Test
    void aCompositionAndARefinementInheritTheFieldNameType() {
        var schema = Tson.standard().resolve(schema("inherit", """
              json_name => !identifier_type { start_add: "@$" }
              node => !record { field_name_type: json_name  fields: [
                { name: "@id"  type: text }  { name: "$ref"  type: text  optional: true } ] }
              labelled => node & { label: text }
              tightened => node ^ { "$ref": text }
            """));
        assertEquals("json_name", ((io.ltr8.tson.schema.meta.RecordBody) schema.schema().entries().get("labelled").body())
                .fieldNameType());
        assertEquals("json_name", ((io.ltr8.tson.schema.meta.RecordBody) schema.schema().entries().get("tightened").body())
                .fieldNameType());
    }

    @Test
    void supertypesOfTwoFieldNameTypesDoNotCompose() {
        var thrown = org.junit.jupiter.api.Assertions.assertThrows(io.ltr8.tson.base.SchemaValidationException.class,
                () -> Tson.standard().resolve(schema("disagree", """
                      json_name => !identifier_type { start_add: "@$" }
                      node => !record { field_name_type: json_name  fields: [ { name: "@id"  type: text } ] }
                      plain => { label: text }
                      both => node & plain & {}
                    """)));
        assertTrue(thrown.getMessage().contains("different field name types"), thrown.getMessage());
    }

    @Test
    void annotationNamesStayIdentifiersByTheGrammar() {
        assertEquals(1, Tson.standard().validate("{ a: @\"x y\" 1 }").size());
    }
}
