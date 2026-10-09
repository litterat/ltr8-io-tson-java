package io.ltr8.tson.json;

import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.io.ByteSource;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A record's field names have a type (SPEC-FEEDBACK.md #10), and a JSON record's member names are its field names
 * ([TSON-JSON] §6.1): a member is matched and judged by its record's {@code field_name_type}, as a TSON text field
 * name is.
 */
class JsonFieldNameTypeTest {

    private static final String ID = "https://example.test/json-field-names.tn";

    private static final String SCHEMA = """
            !!id:"https://example.test/json-field-names.tn"
            !!meta:"https://tson.io/2026/38/m/meta.tn"
            !!import:"https://tson.io/2026/38/m/core.tn"
            {
              json_name => !identifier_type { start_add: "@" }
              node => !record { field_name_type: json_name  fields: [
                { name: "@id"  type: text }  { name: label  type: text } ] }
              row => !record { field_name_type: text  fields: [ { name: "first name"  type: text } ] }
            }
            """;

    private static List<Diagnostic> read(String typeName, String source) {
        Tson tson = Tson.standard();
        tson.resolve(SCHEMA);
        List<Diagnostic> problems = new ArrayList<>();
        DiagnosticsReceiver receiver = problems::add;
        try (ByteSource bytes = ByteSource.of(source)) {
            Json.standard().withSchemas(tson.schemaRegistry()).treeReader().withDiagnostics(receiver).withSchema(ID)
                    .readAs(bytes, typeName);
        }
        return problems;
    }

    @Test
    void aMemberNameIsReadByItsRecordsFieldNameType() {
        assertEquals(List.of(), read("node", "{\"@id\": \"urn:x\", \"label\": \"y\"}"));
        assertEquals(List.of(), read("row", "{\"first name\": \"Ada\"}"));
    }
}
