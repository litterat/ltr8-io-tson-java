package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A document whose schema cannot judge it is not judged ([TSON-DATA] §8.1): when the schema it names cannot be
 * obtained, does not load, or declares no type its root names, the one problem is reported and the value is
 * skipped unread -- parsed, so a syntax error still reports, and held to no other rule. Read as a schemaless
 * record instead, a relaxed name such as {@code "@id"} would draw a verdict the schema might have lifted.
 */
class UnjudgedDocumentTest {

    private static final String DOCUMENT = """
            !!schema:"https://example.test/unjudged.tn"
            !node { "@id": "x"  label: "y" }""";

    private static List<Diagnostic.Code> codes(List<Diagnostic> problems) {
        return problems.stream().map(Diagnostic::code).toList();
    }

    @Test
    void aSchemaThatCannotBeObtainedLeavesTheDocumentUnjudged() {
        List<Diagnostic> problems = Tson.standard().validate(DOCUMENT);
        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.getFirst().code() != Diagnostic.Code.ATOM_FORM_INVALID, problems::toString);
    }

    @Test
    void aSchemaThatDoesNotLoadLeavesTheDocumentUnjudged() {
        String broken = """
                !!id:"https://example.test/unjudged.tn"
                !!meta:"https://tson.io/2026/38/m/meta.tn"
                !!import:"https://tson.io/2026/38/m/core.tn"
                {
                  json_name => !identifier_type { start_add: "@" }
                  node => !record { name_type: json_name  fields: [ { name: "@id"  type: nonesuch } ] }
                }
                """;
        Tson tson = Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(uri -> broken)));
        List<Diagnostic> problems = tson.validate(DOCUMENT);
        assertEquals(List.of(Diagnostic.Code.SCHEMA_ERROR), codes(problems), problems::toString);
    }

    @Test
    void aRootTypeTheSchemaDoesNotDeclareLeavesTheDocumentUnjudged() {
        Tson tson = Tson.standard();
        tson.resolve("""
                !!id:"https://example.test/unjudged.tn"
                !!meta:"https://tson.io/2026/38/m/meta.tn"
                !!import:"https://tson.io/2026/38/m/core.tn"
                { other => { a: text } }
                """);
        List<Diagnostic> problems = tson.validate(DOCUMENT);
        assertEquals(List.of(Diagnostic.Code.UNKNOWN_TYPE), codes(problems), problems::toString);
    }

    /** The skipped value is still parsed: unjudged is not unread, and malformed text is reported. */
    @Test
    void anUnjudgedDocumentsSyntaxIsStillChecked() {
        List<Diagnostic> problems = Tson.standard().validate("""
                !!schema:"https://example.test/unjudged.tn"
                !node { "@id": "x"  ]""");
        assertEquals(2, problems.size(), problems::toString);
        assertTrue(problems.get(1).message().contains("expected a record field name"), problems::toString);
    }
}
