package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.EntryKind;
import io.ltr8.tson.schema.meta.NamespaceBody;
import io.ltr8.tson.schema.meta.TypeRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A namespace type -- an instance of the kernel's {@code namespace}, stating its {@code key_type} and {@code
 * value_type} -- is read as the map it describes. Any schema declares one; a schema importing it holds a value of it
 * as an entry, and types a field by it as by any imported type.
 */
class NamespaceTypeTest {

    private static final String META = "https://example.test/meta-headers.tn";

    private static final String META_TEXT = """
            !!id:"https://example.test/meta-headers.tn"
            !!import:"https://tson.io/2026/39/m/meta-kernel.tn"
            !!import:"https://tson.io/2026/39/m/meta.tn"
            !schema {
              header_name => !identifier_type { continue_add: "-" }
              headers     => !namespace { key_type: header_name  value_type: text }
              %s
            }
            """;

    private static final String USER_TEXT = """
            !!id:"https://example.test/request.tn"
            !!import:"https://example.test/meta-headers.tn"
            !schema {
              defaults => !headers %s
            }
            """;

    private static Tson tson(String more) {
        return Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(
                Map.of(META, META_TEXT.formatted(more))))));
    }

    /** The problems with a governed schema whose one entry is {@code !headers} applied to {@code map}. */
    private static List<Diagnostic> read(String map) {
        return tson("").validateSchema(USER_TEXT.formatted(map));
    }

    @Test
    void aValueIsReadAsAMap() {
        assertEquals(List.of(), read("{ accept => \"text/plain\"  user-agent => \"probe\" }"));
    }

    @Test
    void aKeyIsJudgedByTheKeyType() {
        List<Diagnostic> problems = read("{ \"1st\" => \"text/plain\" }");

        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.getFirst().message().contains("1st"), problems::toString);
    }

    @Test
    void aValueIsJudgedByTheValueType() {
        assertEquals(1, read("{ accept => [text] }").size());
    }

    /** The record of facets is never what is read: a field named for one is a key like any other. */
    @Test
    void theFacetsAreNoFields() {
        assertEquals(List.of(), read("{ key_type => \"x\" }"));
    }

    /** A schema importing the layer types a field by its namespace type, as by any imported type. */
    @Test
    void aSchemaImportingTheLayerTypesAFieldByIt() {
        List<Diagnostic> problems = tson("").validateSchema("""
                !!id:"https://example.test/request.tn"
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !!import:"https://example.test/meta-headers.tn"
                !schema {
                  request => { headers: headers }
                }
                """);

        assertEquals(List.of(), problems);
    }

    @Test
    void theLinkedSchemaRecordsTheMapAndTheKind() {
        TsonLinkedSchema meta = tson("").bindRegistry().core().resolveLinked(META);

        assertEquals(Optional.of(new NamespaceBody(Optional.of(TypeRef.of("header_name")), TypeRef.of("text"), false)),
                meta.namespace("headers"));
        assertEquals(EntryKind.PRODUCT, meta.kindOf("headers"));
        assertEquals(Optional.of("namespace"), meta.schema().constructorOf("headers"));
        assertEquals(Optional.empty(), meta.namespace("header_name"));
    }

    /**
     * The kernel's {@code field_map} states its key family by default, the host record supplying it: its keys are
     * read by spelling and judged by the record's {@code name_type}, so the map it is read as takes any key.
     */
    @Test
    void aDefaultKeyFamilyIsTheHostsAndIsReadBySpelling() {
        NamespaceBody fields = tson("").bindRegistry().core().resolveLinked(META).namespace("field_map")
                .orElseThrow();

        assertEquals(Optional.empty(), fields.keyType());
        assertEquals(false, fields.keysPinned());
        assertEquals(TypeRef.of(NamespaceBody.HOST_KEYS), fields.read().keyType());
    }

    /**
     * A namespace type IS-A {@code top}, and any schema declares one: the kernel's {@code namespace} is in the
     * closure of every schema reaching meta.tn (SPEC-FEEDBACK.md #7).
     */
    @Test
    void anySchemaDeclaresANamespaceType() {
        List<Diagnostic> problems = tson("").validateSchema("""
                !!id:"https://example.test/governed.tn"
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !schema {
                  hs => !namespace { key_type: identifier  value_type: text }
                }
                """);

        assertEquals(List.of(), problems);
    }

    /** A namespace type states the type of its values: an instance of {@code namespace} leaving it out is none. */
    @Test
    void aNamespaceTypeWithoutAValueTypeIsRefused() {
        List<Diagnostic> problems = tson("").validateSchema(META_TEXT.formatted("""
                  keyed => !namespace { key_type: header_name }
                """));

        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.getFirst().message().contains("value_type"), problems::toString);
    }
}
