package io.ltr8.tson.json;

import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [TSON-JSON] §5's reading of a reference: a JSON string, whose text splits at {@code .} before any family is
 * consulted, each segment a name of the family of what it enters ([TSON-SCHEMA] §5.12), as a TSON read judges it.
 */
class JsonReferenceReachTest {

    private static final String ID = "https://example.test/json-reach.tn";

    private static final String SCHEMA = """
            !!id:"https://example.test/json-reach.tn"
            !!import:"https://tson.io/2026/40/m/meta.tn"
            !schema {
              word    => !unicode.identifier_type { medial: " " }
              upper   => !unicode.identifier_type { start: ASCII  continue: ASCII  normalization: ASCII_CASEFOLD }
              api     => !meta.namespace { key_type: meta.identifier  value_type: text }
              spaced  => !meta.namespace { key_type: word  value_type: text }
              verbs   => !meta.namespace { key_type: upper  value_type: text }
              worded  => !meta.record { name_type: word  fields: {}  extension: ABSTRACT }
              site    => worded & { "public api": api  internal: api }
              main    => !site { "public api": !api { ping => "x" }  internal: !api { pong => "y" } }
              book    => !spaced { "get orders" => "g" }
              methods => !verbs { GET => "get" }
              holder  => { r: meta.ref<text> }
            }""";

    private static List<Diagnostic> problems(String reference) {
        SchemaSource source = uri -> SCHEMA;
        Tson tson = Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(source)));
        assertEquals(List.of(), tson.validateSchema(SCHEMA));
        return Json.of(ProcessorConfig.defaults()).withSchemas(tson.schemaRegistry())
                .validate("{\"r\": \"" + reference + "\"}", ID, "holder");
    }

    @Test
    void everyFieldAndKeyIsReachedByAName() {
        for (String reached : List.of("main.public api.ping", "main.internal.pong", "book.get orders",
                "methods.get")) {
            assertEquals(List.of(), problems(reached), reached);
        }
    }

    @Test
    void aNameThatSplitsIntoNoQualifiedNameIsRefused() {
        for (String refused : List.of("main..internal.pong", ".main.internal.pong", "main.internal.pong.", "")) {
            List<Diagnostic> problems = problems(refused);
            assertEquals(1, problems.size(), refused + ": " + problems);
            assertEquals(Diagnostic.Code.ATOM_FORM_INVALID, problems.getFirst().code(), refused);
        }
    }

    @Test
    void aSegmentThatIsNoNameOfItsFamilyIsRefusedByThatFamily() {
        List<Diagnostic> problems = problems("main.internal.po ng");
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().message().contains("'po ng' is no name of 'meta.identifier'"),
                problems.getFirst().message());
    }
}
